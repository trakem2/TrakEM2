/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005-2009 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
/s published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

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

package ini.trakem2.persistence;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.VirtualStack;
import ij.gui.YesNoCancelDialog;
import ij.io.DirectoryChooser;
import ij.io.FileInfo;
import ij.io.FileSaver;
import ij.io.OpenDialog;
import ij.io.Opener;
import ij.plugin.filter.GaussianBlur;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.display.DLabel;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.MipMapImage;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Stack;
import ini.trakem2.imaging.FloatProcessorT2;
import ini.trakem2.imaging.P;
import ini.trakem2.io.ImageSaver;
import ini.trakem2.io.RagMipMaps;
import ini.trakem2.io.RawMipMaps;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.CachingThread;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.awt.image.PixelGrabber;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import mpicbg.trakem2.transform.CoordinateTransform;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.imageplus.FloatImagePlus;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.numeric.real.FloatType;

import org.janelia.intensity.LinearIntensityMap;
import org.xml.sax.InputSource;


/** A class to rely on memory only; except images which are rolled from a folder or their original location and flushed when memory is needed for more. Ideally there would be a given folder for storing items temporarily of permanently as the "project folder", but I haven't implemented it. */
public final class FSLoader extends Loader {

	/* sigma of the Gaussian kernel sto be used for downsampling by a factor of 2 */
	final private static double SIGMA_2 = Math.sqrt( 0.75 );
	
	/** Largest id seen so far. */
	private long max_id = -1;
	/** Largest blob ID seen so far. First valid ID will equal 1. */
	private long max_blob_id = 0;

	private final Map<Long,String> ht_paths = Collections.synchronizedMap(new HashMap<Long,String>());
	/** For saving and overwriting. */
	private String project_file_path = null;
	/** Path to the directory hosting the file image pyramids. */
	private String dir_mipmaps = null;
	/** Path to the directory the user provided when creating the project. */
	private String dir_storage = null;
	/** Path to the directory hosting the alpha masks. */
	private String dir_masks = null;

	/** Path to dir_storage + "trakem2.images/" */
	private String dir_image_storage = null;

	private Set<Patch> touched_mipmaps = Collections.synchronizedSet(new HashSet<Patch>());

	private Set<Patch> mipmaps_to_remove = Collections.synchronizedSet(new HashSet<Patch>());

	/** Used to open a project from an existing XML file. */
	public FSLoader() {
		super(); // register
		FSLoader.startStaticServices();
	}

	private String unuid = null;

	/** Used to create a new project, NOT from an XML file.
	 *  Throws an Exception if the loader cannot read and write to the storage folder. */
	public FSLoader(final String storage_folder) throws Exception {
		this();
		if (null == storage_folder) this.dir_storage = super.getStorageFolder(); // home dir
		else this.dir_storage = storage_folder;
		this.dir_storage = this.dir_storage.replace('\\', '/');
		if (!this.dir_storage.endsWith("/")) this.dir_storage += "/";
		if (!Loader.canReadAndWriteTo(dir_storage)) {
			Utils.log("WARNING can't read/write to the storage_folder at " + dir_storage);
			throw new Exception("Can't write to storage folder " + dir_storage);
		} else {
			this.unuid = createUNUId(this.dir_storage);
			createMipMapsDir(this.dir_storage);
			crashDetector();
		}
	}

	private String createUNUId(String dir_storage) {
		synchronized (db_lock) {
			try {
				if (null == dir_storage) dir_storage = System.getProperty("user.dir") + "/";
				return new StringBuilder(64).append(System.currentTimeMillis()).append('.')
							   .append(Math.abs(dir_storage.hashCode())).append('.')
							   .append(Math.abs(System.getProperty("user.name").hashCode()))
							   .toString();
			} catch (Exception e) {
				IJError.print(e);
			}
		}
		return null;
	}

	/** Store a hidden file in trakem2.mipmaps directory that means: "the project is open", which is deleted when the project is closed. If the file is present on opening a project, it means the project has not been closed properly, and some mipmaps may be wrong. */
	private void crashDetector() {
		if (null == dir_mipmaps) {
			Utils.log2("Could NOT create crash detection system: null dir_mipmaps.");
			return;
		}
		File f = new File(dir_mipmaps + ".open.t2");
		Utils.log2("Crash detector file is " + dir_mipmaps + ".open.t2");
		try {
			if (f.exists()) {
				// crashed!
				notifyMipMapsOutOfSynch();
			} else {
				if (!f.createNewFile() && !dir_mipmaps.startsWith("http:")) {
					Utils.showMessage("WARNING: could NOT create crash detection system:\nCannot write to mipmaps folder.");
				} else {
					Utils.log2("Created crash detection system.");
				}
			}
		} catch (Exception e) {
			Utils.log2("Crash detector error:" + e);
			IJError.print(e);
		}
	}

	public String getProjectXMLPath() {
		if (null == project_file_path) return null;
		return project_file_path.toString(); // a copy of it
	}

	/** Return the folder selected by a user to store files into; it's also the parent folder of the UNUId folder, and the recommended folder to store the XML file into. */
	public String getStorageFolder() {
		if (null == dir_storage) return super.getStorageFolder(); // the user's home
		return dir_storage.toString(); // a copy
	}

	/** Returns a folder proven to be writable for images can be stored into. */
	public String getImageStorageFolder() {
		if (null == dir_image_storage) {
			String s = getUNUIdFolder() + "trakem2.images/";
			File f = new File(s);
			if (f.exists() && f.isDirectory() && f.canWrite()) {
				dir_image_storage = s;
				return dir_image_storage;
			}
			else {
				try {
					f.mkdirs();
					dir_image_storage = s;
				} catch (Exception e) {
					e.printStackTrace();
					return getStorageFolder(); // fall back
				}
			}
		}
		return dir_image_storage;
	}

	/** Returns TMLHandler.getProjectData() . If the path is null it'll be asked for. */
	public Object[] openFSProject(String path, final boolean open_displays) {
		// clean path of double-slashes, safely (and painfully)
		if (null != path) {
			path = path.replace('\\','/');
			path = path.trim();
			int itwo = path.indexOf("//");
			while (-1 != itwo) {
				if (0 == itwo /* samba disk */
				||  (5 == itwo && "http:".equals(path.substring(0, 5)))) {
					// do nothing
				} else {
					path = path.substring(0, itwo) + path.substring(itwo+1);
				}
				itwo = path.indexOf("//", itwo+1);
			}
		}
		//
		if (null == path) {
			OpenDialog od = new OpenDialog("Select Project", OpenDialog.getDefaultDirectory(), null);
			String file = od.getFileName();
			if (null == file || file.toLowerCase().startsWith("null")) return null;
			String dir = od.getDirectory().replace('\\', '/');
			if (!dir.endsWith("/")) dir += "/";
			this.project_file_path = dir + file;
			Utils.log2("project file path 1: " + this.project_file_path);
		} else {
			this.project_file_path = path;
			Utils.log2("project file path 2: " + this.project_file_path);
		}
		Utils.log2("Loader.openFSProject: path is " + path);
		// check if any of the open projects uses the same file path, and refuse to open if so:
		if (null != FSLoader.getOpenProject(project_file_path, this)) {
			Utils.showMessage("The project is already open.");
			return null;
		}

		Object[] data = null;

		// parse file, according to expected format as indicated by the extension:
		final String lcFilePath = this.project_file_path.toLowerCase();
		if (lcFilePath.matches(".*(\\.xml|\\.xml\\.gz)")) {
			InputStream i_stream = null;
			TMLHandler handler = new TMLHandler(this.project_file_path, this);
			if (handler.isUnreadable()) {
				handler = null;
			} else {
				try {
					SAXParserFactory factory = SAXParserFactory.newInstance();
					factory.setValidating(false);
					factory.setXIncludeAware(false);
					SAXParser parser = factory.newSAXParser();
					if (isURL(this.project_file_path)) {
						i_stream = new java.net.URL(this.project_file_path).openStream();
					} else {
						i_stream = new BufferedInputStream(new FileInputStream(this.project_file_path));
					}
					if (lcFilePath.endsWith(".gz")) {
						i_stream  = new GZIPInputStream(i_stream);
					}
					InputSource input_source = new InputSource(i_stream);
					parser.parse(input_source, handler);
				} catch (java.io.FileNotFoundException fnfe) {
					Utils.log("ERROR: File not found: " + path);
					handler = null;
				} catch (Exception e) {
					IJError.print(e);
					handler = null;
				} finally {
					if (null != i_stream) {
						try {
							i_stream.close();
						} catch (Exception e) {
							IJError.print(e);
						}
					}
				}
			}
			if (null == handler) {
				Utils.showMessage("Error when reading the project .xml file.");
				return null;
			}

			data = handler.getProjectData(open_displays);
		}

		if (null == data) {
			Utils.showMessage("Error when parsing the project .xml file.");
			return null;
		}
		// else, good
		crashDetector();
		return data;
	}

	// Only one thread at a time may access this method.
	synchronized static private final Project getOpenProject(final String project_file_path, final Loader caller) {
		if (null == v_loaders) return null;
		final Loader[] lo = (Loader[])v_loaders.toArray(new Loader[0]); // atomic way to get the list of loaders
		for (int i=0; i<lo.length; i++) {
			if (lo[i].equals(caller)) continue;
			if (lo[i] instanceof FSLoader) {
				if (null == ((FSLoader)lo[i]).project_file_path) continue; // not saved
				if (((FSLoader)lo[i]).project_file_path.equals(project_file_path)) {
					return Project.findProject(lo[i]);
				}
			}
		}
		return null;
	}

	static public final Project getOpenProject(final String project_file_path) {
		return getOpenProject(project_file_path, null);
	}
	
	static public final int nStaticServiceThreads() {
		int np = Runtime.getRuntime().availableProcessors();
		// 1 core = 1 thread
		// 2 cores = 2 threads
		// 3+ cores = cores-1 threads
		if (np > 2) np -= 1;
		return np;
	}

	/** Restart the ExecutorService for mipmaps with {@param n_threads}. */
	static public final void restartMipMapThreads(final int n_threads) {
		if (null != regenerator && !regenerator.isShutdown()) {
			regenerator.shutdown();
		}
		regenerator = Utils.newFixedThreadPool(Math.max(1, n_threads), "regenerator");
		Utils.logAll("Restarted mipmap Executor Service for all projects with " + n_threads + " threads.");
	}

	static private void startStaticServices() {
		// Up to nStaticServiceThreads for regenerator and repainter
		if (null == regenerator || regenerator.isShutdown()) {
			regenerator = Utils.newFixedThreadPool(1, "regenerator");
		}
		if (null == repainter || repainter.isShutdown()) {
			repainter = Utils.newFixedThreadPool(nStaticServiceThreads, "repainter"); // for SnapshotPanel
		}
		// Maximum 2 threads for removing files
		if (null == remover || remover.isShutdown()) {
			remover = Utils.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()), "mipmap remover");
		}
		// Just one thread for autosaver
		if (null == autosaver || autosaver.isShutdown()) autosaver = Executors.newScheduledThreadPool(1);
	}

	/** Shutdown the various thread pools and disactivate services in general. */
	static private void destroyStaticServices() {
		if (null != regenerator) regenerator.shutdownNow();
		if (null != remover) remover.shutdownNow();
		if (null != repainter) repainter.shutdownNow();
		if (null != autosaver) autosaver.shutdownNow();
	}

	@Override
	public synchronized void destroy() {
		super.destroy();
		Utils.showStatus("", false);
		// delete mipmap files that where touched and not cleared as saved (i.e. the project was not saved)
		touched_mipmaps.addAll(mipmaps_to_remove);
		Set<Patch> touched = new HashSet<Patch>();
		synchronized (touched_mipmaps) {
			touched.addAll(touched_mipmaps);
		}
		for (final Patch p : touched) {
			File f = new File(getAbsolutePath(p)); // with slice info appended
			//Utils.log2("File f is " + f);
			Utils.log2("Removing mipmaps for " + p);
			// Cannot run in the remover: is a daemon, and would be interrupted.
			removeMipMaps(createIdPath(Long.toString(p.getId()), f.getName(), mExt), (int)p.getWidth(), (int)p.getHeight());
		}
		//
		// remove empty trakem2.mipmaps folder if any
		if (null != dir_mipmaps && !dir_mipmaps.equals(dir_storage)) {
			File f = new File(dir_mipmaps);
			if (f.isDirectory() && 0 == f.list(new FilenameFilter() {
				public boolean accept(File fdir, String name) {
					File file = new File(dir_mipmaps + name);
					if (file.isHidden() || '.' == name.charAt(0)) return false;
					return true;
				}
			}).length) {
				try { f.delete(); } catch (Exception e) { Utils.log("Could not remove empty trakem2.mipmaps directory."); }
			}
		}
		// remove crash detector
		try {
			File fm = new File(dir_mipmaps + ".open.t2");
			if (!fm.delete()) {
				Utils.log2("WARNING: could not delete crash detector file .open.t2 from trakem2.mipmaps folder at " + dir_mipmaps);
			}
		} catch (Exception e) {
			Utils.log2("WARNING: crash detector file trakem.mipmaps/.open.t2 may NOT have been deleted.");
			IJError.print(e);
		}
		if (null == ControlWindow.getProjects() || 1 == ControlWindow.getProjects().size()) {
			destroyStaticServices();
		}
		// remove unuid dir if xml_path is empty (i.e. never saved and not opened from an .xml file)
		if (null == project_file_path) {
			Utils.log2("Removing unuid dir, since project was never saved.");
			final File f = new File(getUNUIdFolder());
			if (null != dir_mipmaps) Utils.removePrefixedFiles(f, "trakem2.mipmaps", null);
			if (null != dir_masks) Utils.removePrefixedFiles(f, "trakem2.masks", null);
			Utils.removePrefixedFiles(f, "features.ser", null);
			Utils.removePrefixedFiles(f, "pointmatches.ser", null);
			// Only if empty:
			if (f.isDirectory()) {
				try {
					if (!f.delete()) {
						Utils.log2("Could not delete unuid directory: likely not empty!");
					}
				} catch (Exception e) {
					Utils.log2("Could not delete unuid directory: " + e);
				}
			}
		}
	}

	/** Get the next unique id, not shared by any other object within the same project. */
	@Override
	public long getNextId() {
		long nid = -1;
		synchronized (db_lock) {
			nid = ++max_id;
		}
		return nid;
	}

	/** Get the next unique id to be used for the {@link Patch}'s {@link CoordinateTransform} or alpha mask. */
	@Override
	public long getNextBlobId() {
		long nid = 0;
		synchronized (db_lock) {
			nid = ++max_blob_id;
		}
		return nid;
	}

	/** Loaded in full from XML file */
	public double[][][] fetchBezierArrays(long id) {
		return null;
	}

	/** Loaded in full from XML file */
	public ArrayList<?> fetchPipePoints(long id) {
		return null;
	}

	/** Loaded in full from XML file */
	public ArrayList<?> fetchBallPoints(long id) {
		return null;
	}

	/** Loaded in full from XML file */
	public Area fetchArea(long area_list_id, long layer_id) { 
		return null;
	}

	/* Note that the min and max is not set -- it's your burden to call setMinAndMax(p.getMin(), p.getMax()) on the returned ImagePlus.getProcessor(). */
	public ImagePlus fetchImagePlus(final Patch p) {
		return (ImagePlus)fetchImage(p, Layer.IMAGEPLUS);
	}

	/** Fetch the ImageProcessor in a synchronized manner, so that there are no conflicts in retrieving the ImageProcessor for a specific stack slice, for example.
	 * Note that the min and max is not set -- it's your burden to call setMinAndMax(p.getMin(), p.getMax()) on the returned ImageProcessor. */
	public ImageProcessor fetchImageProcessor(final Patch p) {
		return (ImageProcessor)fetchImage(p, Layer.IMAGEPROCESSOR);
	}

	/** So far accepts Layer.IMAGEPLUS and Layer.IMAGEPROCESSOR as format. */
	public Object fetchImage(final Patch p, final int format) {
		ImagePlus imp = null;
		ImageProcessor ip = null;
		String slice = null;
		String path = null;
		long n_bytes = 0;
		ImageLoadingLock plock = null;
		synchronized (db_lock) {
			try {
				imp = mawts.get(p.getId());
				path = getAbsolutePath(p);
				int i_sl = -1;
				if (null != path) i_sl = path.lastIndexOf("-----#slice=");
				if (-1 != i_sl) {
					if (null != imp) {
						// check that the stack is large enough (user may have changed it)
						final int ia = Integer.parseInt(path.substring(i_sl + 12));
						if (ia <= imp.getNSlices()) {
							if (null == imp.getStack() || null == imp.getStack().getPixels(ia)) {
								// reload (happens when closing a stack that was opened before importing it, and then trying to paint, for example)
								mawts.removeImagePlus(p.getId());
								imp = null;
							} else {
								imp.setSlice(ia);
								switch (format) {
									case Layer.IMAGEPROCESSOR:
										ip = imp.getStack().getProcessor(ia);
										return ip;
									case Layer.IMAGEPLUS:
										return imp;
									default:
										Utils.log("FSLoader.fetchImage: Unknown format " + format);
										return null;
								}
							}
						} else {
							return null; // beyond bonds!
						}
					}
				}
				// for non-stack images
				if (null != imp) {
					switch (format) {
						case Layer.IMAGEPROCESSOR:
							return imp.getProcessor();
						case Layer.IMAGEPLUS:
							return imp;
						default:
							Utils.log("FSLoader.fetchImage: Unknown format " + format);
							return null;
					}
				}
				if (-1 != i_sl) {
					slice = path.substring(i_sl);
					// set path proper
					path = path.substring(0, i_sl);
				}

				plock = getOrMakeImageLoadingLock(path);
			} catch (Throwable t) {
				handleCacheError(t);
				return null;
			}
		}

		synchronized (plock) {
			imp = mawts.get(p.getId());
			if (null == imp && !p.isPreprocessed()) {
				// Try shared ImagePlus cache
				imp = mawts.get(path); // could have been loaded by a different Patch that uses the same path,
				// such as other slices of a stack or duplicated images.
				if (null != imp) {
					mawts.put(p.getId(), imp, (int)Math.max(p.getWidth(), p.getHeight()));
				}
			}
			if (null != imp) {
				// was loaded by a different thread, or is shareable
				switch (format) {
					case Layer.IMAGEPROCESSOR:
						if (null != slice) {
							return imp.getStack().getProcessor(Integer.parseInt(slice.substring(12)));
						} else {
							return imp.getProcessor();
						}
					case Layer.IMAGEPLUS:
						if (null != slice) {
							imp.setSlice(Integer.parseInt(slice.substring(12)));
						}
						return imp;
					default:
						Utils.log("FSLoader.fetchImage: Unknown format " + format);
						return null;
				}
			}

			// going to load:

			// reserve memory:
			n_bytes = estimateImageFileSize(p, 0);
			releaseToFit(n_bytes);
			imp = openImage(path);

			preProcess(p, imp, n_bytes);

			synchronized (db_lock) {
				try {
					if (null == imp) {
						if (!hs_unloadable.contains(p)) {
							Utils.log("FSLoader.fetchImagePlus: no image exists for patch  " + p + "  at path " + path);
							hs_unloadable.add(p);
						}
						if (ControlWindow.isGUIEnabled()) {
							FilePathRepair.add(p);
						}
						removeImageLoadingLock(plock);
						return null;
					}
					if (null != slice) {
						// set proper active slice
						final int ia = Integer.parseInt(slice.substring(12));
						imp.setSlice(ia);
						if (Layer.IMAGEPROCESSOR == format) ip = imp.getStack().getProcessor(ia); // otherwise creates one new for nothing
					} else {
						// for non-stack images
						// OBSOLETE and wrong //p.putMinAndMax(imp); // non-destructive contrast: min and max -- WRONG, it's destructive for ColorProcessor and ByteProcessor!
							// puts the Patch min and max values into the ImagePlus processor.
						if (Layer.IMAGEPROCESSOR == format) ip = imp.getProcessor();
					}
					mawts.put(p.getId(), imp, (int)Math.max(p.getWidth(), p.getHeight()));
					// imp is cached, so:
					removeImageLoadingLock(plock);

				} catch (Exception e) {
					IJError.print(e);
				}
				switch (format) {
					case Layer.IMAGEPROCESSOR:
						return ip; // not imp.getProcessor because after unlocking the slice may have changed for stacks.
					case Layer.IMAGEPLUS:
						return imp;
					default:
						Utils.log("FSLoader.fetchImage: Unknown format " + format);
						return null;

				}
			}
		}
	}

	/** Returns the alpha mask image from a file, or null if none stored. */
	@Override
	public ByteProcessor fetchImageMask(final Patch p) {
		return p.getAlphaMask();
	}

	@Override
	synchronized public final String getMasksFolder() {
		if (null == dir_masks) createMasksFolder();
		return dir_masks;
	}

	synchronized private final void createMasksFolder() {
		if (null == dir_masks) dir_masks = getUNUIdFolder() + "trakem2.masks/";
		final File f = new File(dir_masks);
		if (f.exists() && f.isDirectory()) return;
		try {
			f.mkdirs();
		} catch (Exception e) {
			IJError.print(e);
		}
	}
	
	private String dir_cts = null;
	
	@Override
	synchronized public final String getCoordinateTransformsFolder() {
		if (null == dir_cts) createCoordinateTransformsFolder();
		return dir_cts;
	}

	synchronized private final void createCoordinateTransformsFolder() {
		if (null == dir_cts) dir_cts = getUNUIdFolder() + "trakem2.cts/";
		final File f = new File(dir_cts);
		if (f.exists() && f.isDirectory()) return;
		try {
			f.mkdirs();
		} catch (Exception e) {
			IJError.print(e);
		}
	}

	/** Loaded in full from XML file */
	public Object[] fetchLabel(DLabel label) {
		return null;
	}

	/** Loads and returns the original image, which is not cached, or returns null if it's not different than the working image. */
	synchronized public ImagePlus fetchOriginal(final Patch patch) {
		String original_path = patch.getOriginalPath();
		if (null == original_path) return null;
		// else, reserve memory and open it:
		releaseToFit(estimateImageFileSize(patch, 0));
		try {
			return openImage(original_path);
		} catch (Throwable t) {
			IJError.print(t);
		}
		return null;
	}

	/* GENERIC, from DBObject calls. Records the id of the object in the HashMap ht_dbo.
	 * Always returns true. Does not check if another object has the same id.
	 */
	public boolean addToDatabase(final DBObject ob) {
		synchronized (db_lock) {
			setChanged(true);
			final long id = ob.getId();
			if (id > max_id) {
				max_id = id;
			}
			if (ob.getClass() == Patch.class) {
				final Patch p = (Patch)ob;
				if (p.hasCoordinateTransform()) {
					max_blob_id = Math.max(p.getCoordinateTransformId(), max_blob_id);
				}
				if (p.hasAlphaMask()) {
					max_blob_id = Math.max(p.getAlphaMaskId(), max_blob_id);
				}
			}
		}
		return true;
	}

	public boolean updateInDatabase(final DBObject ob, final String key) {
		// Should only be GUI-driven
		setChanged(true);
		//
		if (ob.getClass() == Patch.class) {
			Patch p = (Patch)ob;
			if (key.equals("tiff_working")) return null != setImageFile(p, fetchImagePlus(p));
		}
		return true;
	}

	public boolean updateInDatabase(final DBObject ob, final Set<String> keys) {
		// Should only be GUI-driven
		setChanged(true);
		if (ob.getClass() == Patch.class) {
			Patch p = (Patch)ob;
			if (keys.contains("tiff_working")) return null != setImageFile(p, fetchImagePlus(p));
		}
		return true;
	}

	public boolean removeFromDatabase(final DBObject ob) {
		synchronized (db_lock) {
			setChanged(true);
			// remove from the hashtable
			final long loid = ob.getId();
			Utils.log2("removing " + Project.getName(ob.getClass()) + " " + ob);
			if (ob.getClass() == Patch.class) {
				try {
					// STRATEGY change: images are not owned by the FSLoader.
					Patch p = (Patch)ob;
					if (!ob.getProject().getBooleanProperty("keep_mipmaps")) removeMipMaps(p);
					ht_paths.remove(p.getId()); // after removeMipMaps !
					mawts.remove(loid);
					cannot_regenerate.remove(p);
					flushMipMaps(p.getId()); // locks on its own
					touched_mipmaps.remove(p);
					return true;
				} catch (Throwable t) {
					handleCacheError(t);
				}
			}
		}
		return true;
	}

	/** Returns the absolute path to a file that contains the given ImagePlus image - which may be the path as described in the ImagePlus FileInfo object itself, or a totally new file.
	 *  If the Patch p current image path is different than its original image path, then the file is overwritten if it exists already.
	 */
	public String setImageFile(final Patch p, final ImagePlus imp) {
		if (null == imp) return null;
		try {
			String path = getAbsolutePath(p);
			String slice = null;
			//
			// path can be null if the image is pasted, or from a copy, or totally new
			if (null != path) {
				int i_sl = path.lastIndexOf("-----#slice=");
				if (-1 != i_sl) {
					slice = path.substring(i_sl);
					path = path.substring(0, i_sl);
				}
			} else {
				// no path, inspect image FileInfo's path if the image has no changes
				if (!imp.changes) {
					final FileInfo fi = imp.getOriginalFileInfo();
					if (null != fi && null != fi.directory && null != fi.fileName) {
						final String fipath = fi.directory.replace('\\', '/') + "/" + fi.fileName;
						if (new File(fipath).exists()) {
							// no need to save a new image, it exists and has no changes
							updatePaths(p, fipath, null != slice);
							cacheAll(p, imp);
							Utils.log2("Reusing image file: path exists for fileinfo at " + fipath);
							return fipath;
						}
					}
				}
			}
			if (null != path) {
				final String starting_path = path;
				// Save as a separate image in a new path within the storage folder

				String filename = path.substring(path.lastIndexOf('/') +1);

				//Utils.log2("filename 1: " + filename);

				// remove .tif extension if there
				if (filename.endsWith(".tif")) filename = filename.substring(0, filename.length() -3); // keep the dot

				//Utils.log2("filename 2: " + filename);

				// check if file ends with a tag of form ".id1234." where 1234 is p.getId()
				final String tag = ".id" + p.getId() + ".";
				if (!filename.endsWith(tag)) filename += tag.substring(1); // without the starting dot, since it has one already
				// reappend extension
				filename += "tif";

				//Utils.log2("filename 3: " + filename);

				path = getImageStorageFolder() + filename;

				if (path.equals(p.getOriginalPath())) {
					// Houston, we have a problem: a user reused a non-original image
					File file = null;
					int i = 1;
					final int itag = path.lastIndexOf(tag);
					do {
						path = path.substring(0, itag) + "." + i + tag + "tif";
						i++;
						file = new File(path);
					} while (file.exists());
				}

				//Utils.log2("path to use: " + path);

				final String path2 = super.exportImage(p, imp, path, true);

				//Utils.log2("path exported to: " + path2);

				// update paths' hashtable
				if (null != path2) {
					updatePaths(p, path2, null != slice);
					cacheAll(p, imp);
					hs_unloadable.remove(p);
					return path2;
				} else {
					Utils.log("WARNING could not save image at " + path);
					// undo
					updatePaths(p, starting_path, null != slice);
					return null;
				}
			}
		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
	}

	/** Associate patch with imp, and all slices as well if any. */
	private void cacheAll(final Patch p, final ImagePlus imp) {
		if (p.isStack()) {
			for (Patch pa : p.getStackPatches()) {
				cache(pa, imp);
			}
		} else {
			cache(p, imp);
		}
	}

	/** For the Patch and for any associated slices if the patch is part of a stack. */
	private void updatePaths(final Patch patch, final String new_path, final boolean is_stack) {
		synchronized (db_lock) {
			try {
				// ensure the old path is cached in the Patch, to get set as the original if there is no original.
				String old_path = getAbsolutePath(patch);
				if (is_stack) {
					old_path = old_path.substring(0, old_path.lastIndexOf("-----#slice"));
					for (Patch p : patch.getStackPatches()) {
						long pid = p.getId();
						String str = ht_paths.get(pid);
						int isl = str.lastIndexOf("-----#slice=");
						updatePatchPath(p, new_path + str.substring(isl));
					}
				} else {
					Utils.log2("path to set: " + new_path);
					Utils.log2("path before: " + ht_paths.get(patch.getId()));
					updatePatchPath(patch, new_path);
					Utils.log2("path after: " + ht_paths.get(patch.getId()));
				}
				mawts.updateImagePlusPath(old_path, new_path);
			} catch (Throwable e) {
				IJError.print(e);
			}
		}
	}

	/** With slice info appended at the end; only if it exists, otherwise null. */
	public String getAbsolutePath(final Patch patch) {
		synchronized (patch) {
			String abs_path = patch.getCurrentPath();
			if (null != abs_path) return abs_path;
			// else, compute, set and return it:
			String path = ht_paths.get(patch.getId());
			if (null == path) return null;
			// substract slice info if there
			int i_sl = path.lastIndexOf("-----#slice=");
			String slice = null;
			if (-1 != i_sl) {
				slice = path.substring(i_sl);
				path = path.substring(0, i_sl);
			}
			path = getAbsolutePath(path);
			if (null == path) {
				Utils.log("Path for patch " + patch + " does not exist: " + path);
				return null;
			}
			// Else assume that it exists.
			// reappend slice info if existent
			if (null != slice) path += slice;
			// set it
			patch.cacheCurrentPath(path);
			return path;
		}
	}
	
	/** Return an absolute path made from path: if it's already absolute, retursn itself; otherwise, the parent folder of all relative paths of this Loader is prepended. */
	public String getAbsolutePath(String path) {
		if (isRelativePath(path)) {
			// path is relative: preprend the parent folder of the xml file
			path = getParentFolder() + path;
			if (!isURL(path) && !new File(path).exists()) {
				return null;
			}
		}
		return path;
	}

	public final String getImageFilePath(final Patch p) {
		final String path = getAbsolutePath(p);
		if (null == path) return null;
		final int i = path.lastIndexOf("-----#slice");
		return -1 == i ? path
			       : path.substring(0, i);
	}

	public static final boolean isURL(final String path) {
		return null != path && 0 == path.indexOf("http://");
	}

	static public final Pattern ABS_PATH = Pattern.compile("^[a-zA-Z]*:/.*$|^/.*$|[a-zA-Z]:.*$");

	public static final boolean isRelativePath(final String path) {
		return ! ABS_PATH.matcher(path).matches();
	}

	/** All backslashes are converted to slashes to avoid havoc in MSWindows. */
	public void addedPatchFrom(String path, final Patch patch) {
		if (null == path) {
			Utils.log("Null path for patch: " + patch);
			return;
		}
		updatePatchPath(patch, path);
	}

	/** This method has the exclusivity in calling ht_paths.put, because it ensures the path won't have escape characters. */
	private final void updatePatchPath(final Patch patch, String path) { // reversed order in purpose, relative to addedPatchFrom
		// avoid W1nd0ws nightmares
		path = path.replace('\\', '/'); // replacing with chars, in place
		// remove double slashes that a user may have slipped in
		final int start = isURL(path) ? 6 : (IJ.isWindows() ? 3 : 1);
		while (-1 != path.indexOf("//", start)) {
			// avoid the potential C:// of windows and the starting // of a samba network
			path = path.substring(0, start) + path.substring(start).replace("//", "/");
		}
		// cache path as absolute
		patch.cacheCurrentPath(isRelativePath(path) ? getParentFolder() + path : path);
		// if path is absolute, try to make it relative
		//Utils.log2("path was: " + path);
		path = makeRelativePath(path);
		// store
		ht_paths.put(patch.getId(), path);
		//Utils.log2("Updated patch path " + ht_paths.get(patch.getId()) + " for patch " + patch);
	}

	/** Takes a String and returns a copy with the following conversions: / to -, space to _, and \ to -. */
	static public String asSafePath(final String name) {
		return name.trim().replace('/', '-').replace(' ', '_').replace('\\','-');
	}

	/** Overwrites the XML file. If some images do not exist in the file system, a directory with the same name of the XML file plus an "_images" tag appended will be created and images saved there. */
	@Override
	public String save(final Project project, XMLOptions options) {
		String result = null;
		if (null == project_file_path) {
			String xml_path = super.saveAs(project, null, options);
			if (null == xml_path) return null;
			else {
				this.project_file_path = xml_path;
				ControlWindow.updateTitle(project);
				result = this.project_file_path;
			}
		} else {
			File fxml = new File(project_file_path);
			result = super.export(project, fxml, options);
		}
		if (null != result) {
			Utils.logAll(Utils.now() + " Saved " + project);
			touched_mipmaps.clear();
		}
		return result;
	}

	/** The saveAs called from menus via saveTask. */
	@Override
	public String saveAs(Project project, XMLOptions options) {
		String path = super.saveAs(project, null, options);
		if (null != path) {
			// update the xml path to point to the new one
			this.project_file_path = path;
			Utils.log2("After saveAs, new xml path is: " + path);
			touched_mipmaps.clear();
		}
		ControlWindow.updateTitle(project);
		Display.updateTitle(project);
		return path;
	}

	/** Meant for programmatic access, such as calls to project.saveAs(path, overwrite) which call exactly this method. */
	@Override
	public String saveAs(final String path, final XMLOptions options) {
		if (null == path) {
			Utils.log("Cannot save on null path.");
			return null;
		}
		String path2 = path;
		String extension = ".xml";
		if (path2.endsWith(extension)) {} // all fine
		else if (path2.endsWith(".xml.gz")) extension = ".xml.gz";
		else {
			// neither matches, add the default ".xml"
			path2 += extension;
		}
		
		File fxml = new File(path2);
		if (!fxml.canWrite()) {
			// write to storage folder instead
			String path3 = path2;
			path2 = getStorageFolder() + fxml.getName();
			Utils.logAll("WARNING can't write to " + path3 + "\n  --> will write instead to " + path2);
			fxml = new File(path2);
		}
		if (!options.overwriteXMLFile) {
			int i = 1;
			while (fxml.exists()) {
				String parent = fxml.getParent().replace('\\','/');
				if (!parent.endsWith("/")) parent += "/";
				String name = fxml.getName();
				name = name.substring(0, name.length() - 4);
				path2 =  parent + name + "-" +  i + extension;
				fxml = new File(path2);
				i++;
			}
		}
		Project project = Project.findProject(this);
		path2 = super.saveAs(project, path2, options);
		if (null != path2) {
			project_file_path = path2;
			Utils.logAll("After saveAs, new xml path is: " + path2);
			ControlWindow.updateTitle(project);
			touched_mipmaps.clear();
		}
		return path2;
	}

	/** Returns the stored path for the given Patch image, which may be relative and may contain slice information appended.*/
	public String getPath(final Patch patch) {
		return ht_paths.get(patch.getId());
	}

	protected Map<Long,String> getPathsCopy() {
		synchronized (ht_paths) {
			return Collections.synchronizedMap(new HashMap<Long,String>(ht_paths));
		}
	}

	/** Try to make all paths in ht_paths be relative to the given xml_path.
	 *  This is intended for making all paths relative when saving to XML for the first time.
	 *  {@code dir_storage} and {@code dir_mipmaps} remain untouched--otherwise,
	 *  after a {@code saveAs}, images would not be found. */
	protected void makeAllPathsRelativeTo(final String xml_path, final Project project) {
		synchronized (db_lock) {
			try {
				for (final Map.Entry<Long,String> e : ht_paths.entrySet()) {
					e.setValue(FSLoader.makeRelativePath(xml_path, e.getValue()));
				}
				for (final Stack st : project.getRootLayerSet().getAll(Stack.class)) {
					String path = st.getFilePath();
					if (!isRelativePath(path)) {
						String path2 = makeRelativePath(st.getFilePath());
						if (path.equals(path2)) continue; // could not be made relative
						else st.setFilePath(path2); // will also flush the cache, so use only if necessary
					}
				}
			} catch (Throwable t) {
				IJError.print(t);
			}
		}
	}
	protected void restorePaths(final Map<Long,String> copy, final String mipmaps_folder, final String storage_folder) {
		synchronized (db_lock) {
			try {
				this.dir_mipmaps = mipmaps_folder;
				this.dir_storage = storage_folder;
				ht_paths.clear();
				ht_paths.putAll(copy);
			} catch (Throwable t) {
				IJError.print(t);
			}
		}
	}

	/** Takes the given path and tries to makes it relative to this instance's project_file_path, if possible. Otherwise returns the argument as is. */
	public String makeRelativePath(String path) {
		return FSLoader.makeRelativePath(this.project_file_path, path);
	}

	static private String makeRelativePath(final String project_file_path, String path) {
		if (null == project_file_path) {
			//unsaved project
			return path;
		}
		if (null == path) {
			return null;
		}
		// fix W1nd0ws paths
		path = path.replace('\\', '/'); // char-based, no parsing problems
		// remove slice tag
		String slice = null;
		int isl = path.lastIndexOf("-----#slice");
		if (-1 != isl) {
			slice = path.substring(isl);
			path = path.substring(0, isl);
		}
		//
		if (FSLoader.isRelativePath(path)) {
			// already relative
			if (-1 != isl) path += slice;
			return path;
		}
		// the long and verbose way, to be cross-platform. Should work with URLs just the same.
		String xdir = new File(project_file_path).getParentFile().getAbsolutePath();
		if (IJ.isWindows()) {
			xdir = xdir.replace('\\', '/');
			path = path.replace('\\', '/');
		}
		if (!xdir.endsWith("/")) xdir += "/";
		if (path.startsWith(xdir)) {
			path = path.substring(xdir.length());
		}
		if (-1 != isl) path += slice;
		//Utils.log("made relative path: " + path);
		return path;
	}

	/** Adds a "Save" and "Save as" menu items. */
	public void setupMenuItems(final JMenu menu, final Project project) {
		ActionListener listener = new ActionListener() {
			public void actionPerformed(ActionEvent ae) {
				saveTask(project, ae.getActionCommand());
			}
		};
		JMenuItem item;
		item = new JMenuItem("Save"); item.addActionListener(listener); menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0, true));
		item = new JMenuItem("Save as..."); item.addActionListener(listener); menu.add(item);
		final JMenu adv = new JMenu("Advanced");
		item = new JMenuItem("Save as... without coordinate transforms"); item.addActionListener(listener); adv.add(item);
		item = new JMenuItem("Delete stale files..."); item.addActionListener(listener); adv.add(item);
		menu.add(adv);
		menu.addSeparator();
	}

	/** Returns the last Patch. */
	protected Patch importStackAsPatches(final Project project, final Layer first_layer, final double x, final double y, final ImagePlus imp_stack, final boolean as_copy, final String filepath) {
		Utils.log2("FSLoader.importStackAsPatches filepath=" + filepath);
		String target_dir = null;
		if (as_copy) {
			DirectoryChooser dc = new DirectoryChooser("Folder to save images");
			target_dir = dc.getDirectory();
			if (null == target_dir) return null; // user canceled dialog
			if (IJ.isWindows()) target_dir = target_dir.replace('\\', '/');
			if (target_dir.length() -1 != target_dir.lastIndexOf('/')) {
				target_dir += "/";
			}
		}

		// Double.MAX_VALUE is a flag to indicate "add centered"
		double pos_x = Double.MAX_VALUE != x ? x : first_layer.getLayerWidth()/2 - imp_stack.getWidth()/2;
		double pos_y = Double.MAX_VALUE != y ? y : first_layer.getLayerHeight()/2 - imp_stack.getHeight()/2;
		final double thickness = first_layer.getThickness();
		final String title = Utils.removeExtension(imp_stack.getTitle()).replace(' ', '_');
		Utils.showProgress(0);
		Patch previous_patch = null;
		final int n = imp_stack.getStackSize();

		final ImageStack stack = imp_stack.getStack();
		final boolean virtual = stack.isVirtual();
		final VirtualStack vs = virtual ? (VirtualStack)stack : null;

		for (int i=1; i<=n; i++) {
			Layer layer = first_layer;
			double z = first_layer.getZ() + (i-1) * thickness;
			if (i > 1) layer = first_layer.getParent().getLayer(z, thickness, true); // will create new layer if not found
			if (null == layer) {
				Utils.log("Display.importStack: could not create new layers.");
				return null;
			}
			String patch_path = null;

			ImagePlus imp_patch_i = null;
			if (virtual) { // because we love inefficiency, every time all this is done again
				//VirtualStack vs = (VirtualStack)imp_stack.getStack();
				String vs_dir = vs.getDirectory().replace('\\', '/');
				if (!vs_dir.endsWith("/")) vs_dir += "/";
				String iname = vs.getFileName(i);
				patch_path = vs_dir + iname;
				Utils.log2("virtual stack: patch path is " + patch_path);
				releaseToFit(new File(patch_path).length() * 3);
				Utils.log2(i + " : " + patch_path);
				imp_patch_i = openImage(patch_path);
			} else {
				ImageProcessor ip = stack.getProcessor(i);
				if (as_copy) ip = ip.duplicate();
				imp_patch_i = new ImagePlus(title + "__slice=" + i, ip);
			}

			String label = stack.getSliceLabel(i);
			if (null == label) label = "";
			Patch patch = null;
			if (as_copy) {
				patch_path = target_dir + cleanSlashes(imp_patch_i.getTitle()) + ".zip";
				ini.trakem2.io.ImageSaver.saveAsZip(imp_patch_i, patch_path);
				patch = new Patch(project, label + " " + title + " " + i, pos_x, pos_y, imp_patch_i);
			} else if (virtual) {
				patch = new Patch(project, label, pos_x, pos_y, imp_patch_i);
			} else {
				patch_path = filepath + "-----#slice=" + i;
				//Utils.log2("path is "+ patch_path);
				final AffineTransform atp = new AffineTransform();
				atp.translate(pos_x, pos_y);
				patch = new Patch(project, getNextId(), label + " " + title + " " + i, imp_stack.getWidth(), imp_stack.getHeight(), imp_stack.getWidth(), imp_stack.getHeight(), imp_stack.getType(), false, imp_stack.getProcessor().getMin(), imp_stack.getProcessor().getMax(), atp);
				patch.addToDatabase();
				//Utils.log2("type is " + imp_stack.getType());
			}
			Utils.log2("B: " + i + " : " + patch_path);
			addedPatchFrom(patch_path, patch);
			if (!as_copy && !virtual) {
				if (virtual) cache(patch, imp_patch_i); // each slice separately
				else cache(patch, imp_stack); // uses the entire stack, shared among all Patch instances
			}
			if (isMipMapsRegenerationEnabled()) regenerateMipMaps(patch); // submit for regeneration
			if (null != previous_patch) patch.link(previous_patch);
			layer.add(patch);
			previous_patch = patch;
			Utils.showProgress(i * (1.0 / n));
		}
		Utils.showProgress(1.0);

		// update calibration
		// TODO

		// return the last patch
		return previous_patch;
	}

	/** Replace forward slashes and backslashes with hyphens. */
	private final String cleanSlashes(final String s) {
		return s.replace('\\', '-').replace('/', '-');
	}

	/** Specific options for the Loader which exist as attributes to the Project XML node. */
	public void parseXMLOptions(final HashMap<String,String> ht_attributes) {
		// Adding some logic to support old projects which lack a storage folder and a mipmaps folder
		// and also to prevent errors such as those created when manualy tinkering with the XML file
		// or renaming directories, etc.
		String ob = ht_attributes.remove("storage_folder");
		if (null != ob) {
			String sf = ob.replace('\\', '/');
			if (isRelativePath(sf)) {
				sf = getParentFolder() + sf;
			}
			if (isURL(sf)) {
				// can't be an URL
				Utils.log2("Can't have an URL as the path of a storage folder.");
			} else {
				File f = new File(sf);
				if (f.exists() && f.isDirectory()) {
					this.dir_storage = sf;
				} else {
					Utils.log2("storage_folder was not found or is invalid: " + ob);
				}
			}
		}
		if (null == this.dir_storage) {
			// select the directory where the xml file lives.
			this.dir_storage = getParentFolder();
			if (null == this.dir_storage || isURL(this.dir_storage)) this.dir_storage = null;
			if (null == this.dir_storage && ControlWindow.isGUIEnabled()) {
				Utils.log2("Asking user for a storage folder in a dialog."); // tip for headless runners whose program gets "stuck"
				DirectoryChooser dc = new DirectoryChooser("REQUIRED: select a storage folder");
				this.dir_storage = dc.getDirectory();
			}
			if (null == this.dir_storage) {
				IJ.showMessage("TrakEM2 requires a storage folder.\nTemporarily your home directory will be used.");
				this.dir_storage = System.getProperty("user.home");
			}
		}
		// fix
		if (null != this.dir_storage) {
			if (IJ.isWindows()) this.dir_storage = this.dir_storage.replace('\\', '/');
			if (!this.dir_storage.endsWith("/")) this.dir_storage += "/";
		}
		Utils.log2("storage folder is " + this.dir_storage);
		//
		ob = ht_attributes.remove("mipmaps_folder");
		if (null != ob) {
			String mf = ob.replace('\\', '/');
			if (isRelativePath(mf)) {
				mf = getParentFolder() + mf;
			}
			if (isURL(mf)) {
				this.dir_mipmaps = mf;
				// TODO must disable input somehow, so that images are not edited.
			} else {
				File f = new File(mf);
				if (f.exists() && f.isDirectory()) {
					this.dir_mipmaps = mf;
				} else {
					Utils.log2("mipmaps_folder was not found or is invalid: " + ob);
				}
			}
		}
		ob = ht_attributes.remove("mipmaps_regen");
		if (null != ob) {
			this.mipmaps_regen = Boolean.parseBoolean(ob);
		}
		ob = ht_attributes.get("n_mipmap_threads");
		if (null != ob) {
			int n_threads = Math.max(1, Integer.parseInt(ob));
			FSLoader.restartMipMapThreads(n_threads);
		}

		// parse the unuid before attempting to create any folders
		this.unuid = ht_attributes.remove("unuid");

		// Attempt to get an existing UNUId folder, for .xml files that share the same mipmaps folder
		if (ControlWindow.isGUIEnabled() && null == this.unuid) {
			obtainUNUIdFolder();
		}

		if (null == this.dir_mipmaps) {
			// create a new one inside the dir_storage, which can't be null
			createMipMapsDir(dir_storage);
			if (null != this.dir_mipmaps && ControlWindow.isGUIEnabled() && null != IJ.getInstance()) {
				notifyMipMapsOutOfSynch();
			}
		}
		// fix
		if (null != this.dir_mipmaps && !this.dir_mipmaps.endsWith("/")) this.dir_mipmaps += "/";
		Utils.log2("mipmaps folder is " + this.dir_mipmaps);

		if (null == unuid) {
			IJ.log("OLD VERSION DETECTED: your trakem2\nproject has been updated to the new format.\nPlease SAVE IT to avoid regenerating\ncached data when reopening it.");
			Utils.log2("Creating unuid for project " + this);
			this.unuid = createUNUId(dir_storage);
			fixStorageFolders();
			Utils.log2("Now mipmaps folder is " + this.dir_mipmaps);
			if (null != dir_masks) Utils.log2("Now masks folder is " + this.dir_masks);
		}

		final String s_mipmaps_format = (String) ht_attributes.remove("mipmaps_format");
		if (null != s_mipmaps_format) {
			final int mipmaps_format = Integer.parseInt(s_mipmaps_format.trim());
			if (mipmaps_format >= 0 && mipmaps_format < MIPMAP_FORMATS.length) {
				Utils.log2("Set mipmap format to " + mipmaps_format);
				setMipMapFormat(mipmaps_format);
			}
		}
	}

	private void notifyMipMapsOutOfSynch() {
		Utils.log2("'ok' dialog to explain that mipmaps may be in disagreement with the XML file."); // tip for headless runners whose program gets "stuck"
		Utils.showMessage("TrakEM2 detected a crash", "TrakEM2 detected a crash. Image mipmap files may be out of synch.\n\nIf you where editing images when the crash occurred,\nplease right-click and run 'Project - Regenerate all mipmaps'");
	}

	/** Order the regeneration of all mipmaps for the Patch instances in @param patches, setting up a task that blocks input until all completed. */
	public Bureaucrat regenerateMipMaps(final Collection<? extends Displayable> patches) {
		return Bureaucrat.createAndStart(new Worker.Task("Regenerating mipmaps") { public void exec() {
			final List<Future<?>> fus = new ArrayList<Future<?>>();
			for (final Displayable d : patches) {
				if (d.getClass() != Patch.class) continue;
				fus.add(d.getProject().getLoader().regenerateMipMaps((Patch) d));
			}
			// Wait until all done
			for (final Future<?> fu : fus) try {
				if (null != fu) fu.get(); // fu could be null if a task was not submitted because it's already being done or it failed in some way.
			} catch (Exception e) { IJError.print(e); }
		}}, Project.findProject(this));
	}


	/** Specific options for the Loader which exist as attributes to the Project XML node. */
	@Override
	public void insertXMLOptions(final StringBuilder sb_body, final String indent) {
		sb_body.append(indent).append("unuid=\"").append(unuid).append("\"\n");
		if (null != dir_mipmaps) sb_body.append(indent).append("mipmaps_folder=\"").append(makeRelativePath(dir_mipmaps)).append("\"\n");
		if (null != dir_storage) sb_body.append(indent).append("storage_folder=\"").append(makeRelativePath(dir_storage)).append("\"\n");
		sb_body.append(indent).append("mipmaps_format=\"").append(mipmaps_format).append("\"\n");
	}

	/** Return the path to the folder containing the project XML file. */
	public final String getParentFolder() {
		return this.project_file_path.substring(0, this.project_file_path.lastIndexOf('/')+1);
	}

	/* ************** MIPMAPS **********************/

	/** Returns the path to the directory hosting the file image pyramids. */
	public String getMipMapsFolder() {
		return dir_mipmaps;
	}

	/*
	static private IndexColorModel thresh_cm = null;

	static private final IndexColorModel getThresholdLUT() {
		if (null == thresh_cm) {
			// An array of all black pixels (value 0) except at 255, which is white (value 255).
			final byte[] c = new byte[256];
			c[255] = (byte)255;
			thresh_cm = new IndexColorModel(8, 256, c, c, c);
		}
		return thresh_cm;
	}
	*/

	/** Returns the array of pixels, whose type depends on the bi.getType(); for example, for a BufferedImage.TYPE_BYTE_INDEXED, returns a byte[]. */
	static public final Object grabPixels(final BufferedImage bi) {
		final PixelGrabber pg = new PixelGrabber(bi, 0, 0, bi.getWidth(), bi.getHeight(), false);
		try {
			pg.grabPixels();
			return pg.getPixels();
		} catch (InterruptedException e) {
			IJError.print(e);
		}
		return null;
	}

	private final BufferedImage createCroppedAlpha(final BufferedImage alpha, final BufferedImage outside) {
		if (null == outside) return alpha;

		final int width = outside.getWidth();
		final int height = outside.getHeight();

		// Create an outside image, thresholded: only pixels of 255 remain as 255, the rest is set to 0.
		/* // DOESN'T work: creates a mask with "black" as 254 (???), and white 255 (correct).
		final BufferedImage thresholded = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, getThresholdLUT());
		thresholded.createGraphics().drawImage(outside, 0, 0, null);
		*/

		// So, instead: grab the pixels, fix them manually
		// The cast to byte[] works because "outside" and "alpha" are TYPE_BYTE_INDEXED.
		final byte[] o = (byte[])grabPixels(outside); 
		if (null == o) return null;
		final byte[] a = null == alpha ? o : (byte[])grabPixels(alpha);

		// Set each non-255 pixel in outside to 0 in alpha:
		for (int i=0; i<o.length; i++) {
			if ( (o[i]&0xff) < 255) a[i] = 0;
		}

		// Put the pixels back into an image:
		final BufferedImage thresholded = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, Loader.GRAY_LUT);
		thresholded.getRaster().setDataElements(0, 0, width, height, a);

		return thresholded;
	}
	
	/** WARNING will resize the FloatProcessorT2 source in place, unlike ImageJ standard FloatProcessor class. */
	static final private byte[] gaussianBlurResizeInHalf(final FloatProcessorT2 source)
	{
		new GaussianBlur().blurFloat( source, SIGMA_2, SIGMA_2, 0.01 );
		source.halfSizeInPlace();
		
		return (byte[])source.convertToByte(false).getPixels(); // no scaling
	}

	/** Queue/unqueue for mipmap removal on shutdown without saving;
	 * the {@param yes}, when true, makes the {@param p} be queued,
	 * and when false, be removed from the queue. */
	public void queueForMipmapRemoval(final Patch p, boolean yes) {
		if (yes) touched_mipmaps.add(p);
		else touched_mipmaps.remove(p);
	}

	/** Queue/unqueue for mipmap removal on shutdown without saving;
	 * the {@param yes}, when true, makes the {@param p} be queued,
	 * and when false, be removed from the queue. */
	public void tagForMipmapRemoval(final Patch p, final boolean yes) {
		if (yes) mipmaps_to_remove.add(p);
		else mipmaps_to_remove.remove(p);
	}

	/** Given an image and its source file name (without directory prepended), generate
	 * a pyramid of images until reaching an image not smaller than 32x32 pixels.<br />
	 * Such images are stored as jpeg 85% quality in a folder named trakem2.mipmaps.<br />
	 * The Patch id and the right extension will be appended to the filename in all cases.<br />
	 * Any equally named files will be overwritten. */
	protected boolean generateMipMaps(final Patch patch) {
		Utils.log2("mipmaps for " + patch);
		final String path = getAbsolutePath(patch);
		if (null == path) {
			Utils.log("generateMipMaps: null path for Patch " + patch);
			cannot_regenerate.add(patch);
			return false;
		}
		if (hs_unloadable.contains(patch)) {
			FilePathRepair.add(patch);
			return false;
		}
		synchronized (gm_lock) {
			try {
				if (null == dir_mipmaps) createMipMapsDir(null);
				if (null == dir_mipmaps || isURL(dir_mipmaps)) return false;
			} catch (Exception e) {
				IJError.print(e);
			}
		}

		/** Record Patch as modified */
		touched_mipmaps.add(patch);

		/** Remove serialized features, if any */
		removeSerializedFeatures(patch);

		/** Remove serialized pointmatches, if any */
		removeSerializedPointMatches(patch);

		/** Alpha mask: setup to check if it was modified while regenerating. */
		final long alpha_mask_id = patch.getAlphaMaskId();

		final int resizing_mode = patch.getProject().getMipMapsMode();

		try {
			ImageProcessor ip;
			ByteProcessor alpha_mask = null;
			ByteProcessor outside_mask = null;
			int type = patch.getType();

			// Aggressive cache freeing
			releaseToFit(patch.getOWidth() * patch.getOHeight() * 4 + MIN_FREE_BYTES);

			// Obtain an image which may be coordinate-transformed, and an alpha mask.
			Patch.PatchImage pai = patch.createTransformedImage();
			if (null == pai || null == pai.target) {
				Utils.log("Can't regenerate mipmaps for patch " + patch);
				cannot_regenerate.add(patch);
				return false;
			}
			ip = pai.target;
			alpha_mask = pai.mask; // can be null
			outside_mask = pai.outside; // can be null
			pai = null;
			
			// Old style:
			//final String filename = new StringBuilder(new File(path).getName()).append('.').append(patch.getId()).append(mExt).toString();
			// New style:
			final String filename = createMipMapRelPath(patch, mExt);

			int w = ip.getWidth();
			int h = ip.getHeight();

			// sigma = sqrt(2^level - 0.5^2)
			//    where 0.5 is the estimated sigma for a full-scale image
			//  which means sigma = 0.75 for the full-scale image (has level 0)
			// prepare a 0.75 sigma image from the original

			double min = patch.getMin(),
			       max = patch.getMax();
			// Fix improper min,max values
			// (The -1,-1 are flags really for "not set")
			if (-1 == min && -1 == max) {
				switch (type) {
					case ImagePlus.COLOR_RGB:
					case ImagePlus.COLOR_256:
					case ImagePlus.GRAY8:
						patch.setMinAndMax(0, 255);
						break;
					// Find and flow through to default:
					case ImagePlus.GRAY16:
						((ij.process.ShortProcessor)ip).findMinAndMax();
						patch.setMinAndMax(ip.getMin(), ip.getMax());
						break;
					case ImagePlus.GRAY32:
						((FloatProcessor)ip).findMinAndMax();
						patch.setMinAndMax(ip.getMin(), ip.getMax());
						break;
				}
				min = patch.getMin(); // may have changed
				max = patch.getMax();
			}
			
			// Set for the level 0 image, which is a duplicate of the one in the cache in any case
			ip.setMinAndMax(min, max);


			// ImageJ no longer stretches the bytes for ByteProcessor with setMinAndmax
			if (ByteProcessor.class == ip.getClass()) {
				if (0 != min && 255 != max) {
					final byte[] b = (byte[]) ip.getPixels();
					final double scale = 255 / (max - min);
					for (int i=0; i<b.length; ++i) {
						final int val = b[i] & 0xff;
						if (val < min) b[i] = 0;
						else b[i] = (byte)Math.min(255, ((val - min) * scale));
					}
				}
			}

			// Proper support for LUT images: treat them as RGB
			if (ip.isColorLut() || type == ImagePlus.COLOR_256) {
				ip = ip.convertToRGB();
				type = ImagePlus.COLOR_RGB;
			}
			
			if (Loader.AREA_DOWNSAMPLING == resizing_mode) {
				long t0 = System.currentTimeMillis();
				final ImageBytes[] b = DownsamplerMipMaps.create(patch, type, ip, alpha_mask, outside_mask);
				long t1 = System.currentTimeMillis();
				for (int i=0; i<b.length; ++i) {
					mmio.save(getLevelDir(dir_mipmaps, i) + filename, b[i].c, b[i].width, b[i].height, 0.85f);
				}
				long t2 = System.currentTimeMillis();
				System.out.println("MipMaps with area downsampling: creation took " + (t1 - t0) + "ms, saving took " + (t2 - t1) + "ms, total: " + (t2 - t0) + "ms\n");
			} else if (Loader.GAUSSIAN == resizing_mode) {
				if (ImagePlus.COLOR_RGB == type) {
					// TODO releaseToFit proper
					releaseToFit(w * h * 4 * 10);
					final ColorProcessor cp = (ColorProcessor)ip;
					final FloatProcessorT2 red = new FloatProcessorT2(w, h, 0, 255);   cp.toFloat(0, red);
					final FloatProcessorT2 green = new FloatProcessorT2(w, h, 0, 255); cp.toFloat(1, green);
					final FloatProcessorT2 blue = new FloatProcessorT2(w, h, 0, 255);  cp.toFloat(2, blue);
					FloatProcessorT2 alpha;
					final FloatProcessorT2 outside;
					if (null != alpha_mask) {
						alpha = new FloatProcessorT2(alpha_mask);
					} else {
						alpha = null;
					}
					if (null != outside_mask) {
						outside = new FloatProcessorT2(outside_mask);
						if ( null == alpha ) {
							alpha = outside;
							alpha_mask = outside_mask;
						}
					} else {
						outside = null;
					}

					final String target_dir0 = getLevelDir(dir_mipmaps, 0);

					if (Thread.currentThread().isInterrupted()) return false;

					// Generate level 0 first:
					// TODO Add alpha information into the int[] pixel array or make the image visible some other way
					if (!(null == alpha ? mmio.save(cp, target_dir0 + filename, 0.85f, false)
							: mmio.save(target_dir0 + filename, P.asRGBABytes((int[])cp.getPixels(), (byte[])alpha_mask.getPixels(), null == outside ? null : (byte[])outside_mask.getPixels()), w, h, 0.85f))) {
						Utils.log("Failed to save mipmap for COLOR_RGB, 'alpha = " + alpha + "', level = 0  for  patch " + patch);
						cannot_regenerate.add(patch);
					} else {
						int k = 0; // the scale level. Proper scale is: 1 / pow(2, k)
						do {
							if (Thread.currentThread().isInterrupted()) return false;
							// 1 - Prepare values for the next scaled image
							k++;
							// 2 - Check that the target folder for the desired scale exists
							final String target_dir = getLevelDir(dir_mipmaps, k);
							if (null == target_dir) continue;
							// 3 - Blur the previous image to 0.75 sigma, and scale it
							final byte[] r = gaussianBlurResizeInHalf(red);   // will resize 'red' FloatProcessor in place.
							final byte[] g = gaussianBlurResizeInHalf(green); // idem
							final byte[] b = gaussianBlurResizeInHalf(blue);  // idem
							final byte[] a = null == alpha ? null : gaussianBlurResizeInHalf(alpha); // idem
							if ( null != outside ) {
								final byte[] o;
								if (alpha != outside)
									o = gaussianBlurResizeInHalf(outside); // idem
								else
									o = a;
								// Remove all not completely inside pixels from the alphamask
								// If there was no alpha mask, alpha is the outside itself
								for (int i=0; i<o.length; i++) {
									if ( (o[i]&0xff) != 255 ) a[i] = 0; // TODO I am sure there is a bitwise operation to do this in one step. Some thing like: a[i] &= 127;
								}
							}

							w = red.getWidth();
							h = red.getHeight();

							// 4 - Compose ColorProcessor
							if (null == alpha) {
								// 5 - Save as jpeg
								if (!mmio.save(target_dir + filename, new byte[][]{r, g, b}, w, h, 0.85f)) {
									Utils.log("Failed to save mipmap for COLOR_RGB, 'alpha = " + alpha + "', level = " + k  + " for  patch " + patch);
									cannot_regenerate.add(patch);
									break;
								}
							} else {
								if (!mmio.save(target_dir + filename, new byte[][]{r, g, b, a}, w, h, 0.85f)) {
									Utils.log("Failed to save mipmap for COLOR_RGB, 'alpha = " + alpha + "', level = " + k  + " for  patch " + patch);
									cannot_regenerate.add(patch);
									break;
								}
							}
						} while (w >= 32 && h >= 32); // not smaller than 32x32
					}
				} else {
					long t0 = System.currentTimeMillis();
					// Greyscale:
					releaseToFit(w * h * 4 * 10);

					if (Thread.currentThread().isInterrupted()) return false;

					final FloatProcessorT2 fp = new FloatProcessorT2((FloatProcessor) ip.convertToFloat());
					if (ImagePlus.GRAY8 == type) {
						// for 8-bit, the min,max has been applied when going to FloatProcessor
						fp.setMinMax(0, 255); // just set it
					} else {
						fp.setMinAndMax(patch.getMin(), patch.getMax());
					}
					//fp.debugMinMax(patch.toString());

					FloatProcessorT2 alpha, outside;
					if (null != alpha_mask) {
						alpha = new FloatProcessorT2(alpha_mask);
					} else {
						alpha = null;
					}
					if (null != outside_mask) {
						outside = new FloatProcessorT2(outside_mask);
						if (null == alpha) {
							alpha = outside;
							alpha_mask = outside_mask;
						}
					} else {
						outside = null;
					}

					int k = 0; // the scale level. Proper scale is: 1 / pow(2, k)
					do {
						if (Thread.currentThread().isInterrupted()) return false;

						if (0 != k) { // not doing so at the end because it would add one unnecessary blurring
							gaussianBlurResizeInHalf( fp );
							if (null != alpha) {
								gaussianBlurResizeInHalf( alpha );
								if (alpha != outside && outside != null) {
									gaussianBlurResizeInHalf( outside );
								}
							}
						}

						w = fp.getWidth();
						h = fp.getHeight();

						// 1 - check that the target folder for the desired scale exists
						final String target_dir = getLevelDir(dir_mipmaps, k);
						if (null == target_dir) continue;

						if (null != alpha) {
							// 3 - save as jpeg with alpha
							// Remove all not completely inside pixels from the alpha mask
							// If there was no alpha mask, alpha is the outside itself
							if (!mmio.save(target_dir + filename, new byte[][]{fp.getScaledBytePixels(), P.merge(alpha.getBytePixels(), null == outside ? null : outside.getBytePixels())}, w, h, 0.85f)) {
								Utils.log("Failed to save mipmap for GRAY8, 'alpha = " + alpha + "', level = " + k  + " for  patch " + patch);
								cannot_regenerate.add(patch);
								break;
							}
						} else {
							// 3 - save as 8-bit jpeg
							if (!mmio.save(target_dir + filename, new byte[][]{fp.getScaledBytePixels()}, w, h, 0.85f)) {
								Utils.log("Failed to save mipmap for GRAY8, 'alpha = " + alpha + "', level = " + k  + " for  patch " + patch);
								cannot_regenerate.add(patch);
								break;
							}
						}

						// 4 - prepare values for the next scaled image
						k++;
					} while (fp.getWidth() >= 32 && fp.getHeight() >= 32); // not smaller than 32x32

					long t1 = System.currentTimeMillis();
					System.out.println("MipMaps took " + (t1 - t0));
				}
			} else {
				Utils.log("ERROR: unknown image resizing mode for mipmaps: " + resizing_mode);
			}

			return true;
		} catch (Throwable e) {
			Utils.log("*** ERROR: Can't generate mipmaps for patch " + patch);
			IJError.print(e);
			cannot_regenerate.add(patch);
			return false;
		} finally {

			// flush any cached tiles
			flushMipMaps(patch.getId());

			// flush any cached layer screenshots
			if (null != patch.getLayer()) {
				try { patch.getLayer().getParent().removeFromOffscreens(patch.getLayer()); } catch (Exception e) { IJError.print(e); }
			}

			// gets executed even when returning from the catch statement or within the try/catch block
			synchronized (gm_lock) {
				regenerating_mipmaps.remove(patch);
			}

			// Has the alpha mask changed?
			if (patch.getAlphaMaskId() != alpha_mask_id) {
				Utils.log2("Alpha mask changed: resubmitting mipmap regeneration for " + patch);
				regenerateMipMaps(patch);
			}
		}
	}


	/** Remove the file, if it exists, with serialized features for patch.
	 * Returns true when no such file or on success; false otherwise. */
	public boolean removeSerializedFeatures(final Patch patch) {
		final File f = new File(new StringBuilder(getUNUIdFolder()).append("features.ser/").append(FSLoader.createIdPath(Long.toString(patch.getId()), "features", ".ser")).toString());
		if (f.exists()) {
			try {
				return f.delete();
			} catch (Exception e) {
				IJError.print(e);
				return false;
			}
		} else return true;
	}

	/** Remove the file, if it exists, with serialized point matches for patch.
	 * Returns true when no such file or on success; false otherwise. */
	public boolean removeSerializedPointMatches(final Patch patch) {
		final String ser = new StringBuilder(getUNUIdFolder()).append("pointmatches.ser/").toString();
		final File fser = new File(ser);

		if (!fser.exists() || !fser.isDirectory()) return true;

		boolean success = true;
		final String sid = Long.toString(patch.getId());

		final ArrayList<String> removed_paths = new ArrayList<String>();

		// 1 - Remove all files with <p1.id>_<p2.id>:
		if (sid.length() < 2) {
			// Delete all files starting with sid + '_' and present directly under fser
			success = Utils.removePrefixedFiles(fser, sid + "_", removed_paths);
		} else {
			final String sid_ = sid + "_"; // minimal 2 length: a number and the underscore
			final int len = sid_.length();
			final StringBuilder dd = new StringBuilder();
			for (int i=1; i<=len; i++) {
				dd.append(sid_.charAt(i-1));
				if (0 == i % 2 && len != i) dd.append('/');
			}
			final String med = dd.toString();
			final int last_slash = med.lastIndexOf('/');
			final File med_parent = new File(ser + med.substring(0, last_slash+1));
			// case of 12/34/_*    ---> use prefix: "_"
			// case of 12/34/5_/*  ---> use prefix: last number plus underscore, aka: med.substring(med.length()-2);
			success = Utils.removePrefixedFiles(med_parent, 
							    last_slash == med.length() -2 ? "_" : med.substring(med.length() -2),
							    removed_paths);
		}

		// 2 - For each removed path, find the complementary: <*>_<p1.id>
		for (String path : removed_paths) {
			if (IJ.isWindows()) path = path.replace('\\', '/');
			File f = new File(path);
			// Check that its a pointmatches file
			int idot = path.lastIndexOf(".pointmatches.ser");
			if (idot < 0) {
				Utils.log2("Not a pointmatches.ser file: can't process " + path);
				continue;
			}

			// Find the root
			int ifolder = path.indexOf("pointmatches.ser/");
			if (ifolder < 0) {
				Utils.log2("Not in pointmatches.ser/ folder:" + path);
				continue;
			}
			String dir = path.substring(0, ifolder + 17);

			// Cut the beginning and the end
			String name = path.substring(dir.length(), idot);
			Utils.log2("name: " + name);
			// Remove all path separators
			name = name.replaceAll("/", "");

			int iunderscore = name.indexOf('_');
			if (-1 == iunderscore) {
				Utils.log2("No underscore: can't process " + path);
				continue;
			}
			name = FSLoader.createIdPath(new StringBuilder().append(name.substring(iunderscore+1)).append('_').append(name.substring(0, iunderscore)).toString(), "pointmatches", ".ser");

			f = new File(dir + name);
			if (f.exists()) {
				if (!f.delete()) {
					Utils.log2("Could not delete " + f.getAbsolutePath());
					success = false;
				} else {
					Utils.log2("Deleted pointmatches file " + name);
					// Now remove its parent directories within pointmatches.ser/ directory, if they are empty
					int islash = name.lastIndexOf('/');
					String dirname = name;
					while (islash > -1) {
						dirname = dirname.substring(0, islash);
						if (!Utils.removeFile(new File(dir + dirname))) {
							// directory not empty
							break;
						}
						islash = dirname.lastIndexOf('/');
					}
				}
			} else {
				Utils.log2("File does not exist: " + dir + name);
			}
		}

		return success;
	}

	/** Generate image pyramids and store them into files under the dir_mipmaps for each Patch object in the Project. The method is multithreaded, using as many processors as available to the JVM.
	 *
	 * @param al : the list of Patch instances to generate mipmaps for.
	 * @param overwrite : whether to overwrite any existing mipmaps, or save only those that don't exist yet for whatever reason. This flag provides the means for minimal effort mipmap regeneration.)
	 * */
	public Bureaucrat generateMipMaps(final Collection<Displayable> patches, final boolean overwrite) {
		if (null == patches || 0 == patches.size()) return null;
		if (null == dir_mipmaps) createMipMapsDir(null);
		if (isURL(dir_mipmaps)) {
			Utils.log("Mipmaps folder is an URL, can't save files into it.");
			return null;
		}
		return Bureaucrat.createAndStart(new Worker.Task("Generating MipMaps") {
			public void exec() {
				this.setAsBackground(true);
				Utils.log2("starting mipmap generation ..");
				try {
					final ArrayList<Future<?>> fus = new ArrayList<Future<?>>();
					for (final Displayable displ : patches) {
						if (displ.getClass() != Patch.class) continue;
						Patch pa = (Patch)displ;
						boolean ow = overwrite;
						if (!overwrite) {
							// check if all the files exist. If one doesn't, then overwrite all anyway
							int w = (int)pa.getWidth();
							int h = (int)pa.getHeight();
							int level = 0;
							final String filename = new File(getAbsolutePath(pa)).getName() + "." + pa.getId() + mExt;
							do {
								w /= 2;
								h /= 2;
								level++;
								if (!new File(dir_mipmaps + level + "/" + filename).exists()) {
									ow = true;
									break;
								}
							} while (w >= 32 && h >= 32);
						}
						if (!ow) continue;
						fus.add(regenerateMipMaps(pa));
					}

					Utils.wait(fus);

				} catch (Exception e) {
					IJError.print(e);
				}
			}
		}, ((Displayable)patches.iterator().next()).getProject());
	}

	static private final Object FSLOCK = new Object();

	private final String getLevelDir(final String dir_mipmaps, final int level) {
		// synch, so that multithreaded generateMipMaps won't collide trying to create dirs
		synchronized (FSLOCK) {
			final String path = new StringBuilder(dir_mipmaps).append(level).append('/').toString();
			if (isURL(dir_mipmaps)) {
				return path;
			}
			final File file = new File(path);
			if (file.exists() && file.isDirectory()) {
				return path;
			}
			// else, create it
			try {
				file.mkdir();
				return path;
			} catch (Exception e) {
				IJError.print(e);
				return null;
			}
		}
	}

	/** Returns the near-unique folder for the project hosted by this FSLoader. */
	public String getUNUIdFolder() {
		return new StringBuilder(getStorageFolder()).append("trakem2.").append(unuid).append('/').toString();
	}

	/** Return the unuid_dir or null if none valid selected. */
	private String obtainUNUIdFolder() {
		YesNoCancelDialog yn = ControlWindow.makeYesNoCancelDialog("Old .xml version!", "The loaded XML file does not contain an UNUId. Select a shared UNUId folder?\nShould look similar to: trakem2.12345678.12345678.12345678");
		if (!yn.yesPressed()) return null;
		DirectoryChooser dc = new DirectoryChooser("Select UNUId folder");
		String unuid_dir = dc.getDirectory();
		String unuid_dir_name = new File(unuid_dir).getName();
		Utils.log2("Selected UNUId folder: " + unuid_dir + "\n with name: " + unuid_dir_name);
		if (null != unuid_dir) {
			if (IJ.isWindows()) unuid_dir = unuid_dir.replace('\\', '/');
			if ( ! unuid_dir_name.startsWith("trakem2.")) {
				Utils.logAll("Invalid UNUId folder: must start with \"trakem2.\". Try again or cancel.");
				return obtainUNUIdFolder();
			} else {
				String[] nums = unuid_dir_name.split("\\.");
				if (nums.length != 4) {
					Utils.logAll("Invalid UNUId folder: needs trakem + 3 number blocks. Try again or cancel.");
					return obtainUNUIdFolder();
				}
				for (int i=1; i<nums.length; i++) {
					try {
						Long.parseLong(nums[i]);
					} catch (NumberFormatException nfe) {
						Utils.logAll("Invalid UNUId folder: at least one block is not a number. Try again or cancel.");
						return obtainUNUIdFolder();
					}
				}
				// ok, aceptamos pulpo
				String unuid = unuid_dir_name.substring(8); // remove prefix "trakem2."
				if (unuid.endsWith("/")) unuid = unuid.substring(0, unuid.length() -1);
				this.unuid = unuid;

				if (!unuid_dir.endsWith("/")) unuid_dir += "/";

				String dir_storage = new File(unuid_dir).getParent().replace('\\', '/');
				if (!dir_storage.endsWith("/")) dir_storage += "/";
				this.dir_storage = dir_storage;

				this.dir_mipmaps = unuid_dir + "trakem2.mipmaps/";

				return unuid_dir;
			}
		}
		return null;
	}

	/** If parent path is null, it's asked for.*/
	private boolean createMipMapsDir(String parent_path) {
		if (null == this.unuid) this.unuid = createUNUId(parent_path);
		if (null == parent_path) {
			// try to create it in the same directory where the XML file is
			if (null != dir_storage) {
				File f = new File(getUNUIdFolder() + "/trakem2.mipmaps");
				if (!f.exists()) {
					try {
						if (f.mkdir()) {
							this.dir_mipmaps = f.getAbsolutePath().replace('\\', '/');
							if (!dir_mipmaps.endsWith("/")) this.dir_mipmaps += "/";
							return true;
						}
					} catch (Exception e) {}
				} else if (f.isDirectory()) {
					this.dir_mipmaps = f.getAbsolutePath().replace('\\', '/');
					if (!dir_mipmaps.endsWith("/")) this.dir_mipmaps += "/";
					return true;
				}
				// else can't use it
			}
			// else,  ask for a new folder
			final DirectoryChooser dc = new DirectoryChooser("Select MipMaps parent directory");
			parent_path = dc.getDirectory();
			if (null == parent_path) return false;
			if (IJ.isWindows()) parent_path = parent_path.replace('\\', '/');
			if (!parent_path.endsWith("/")) parent_path += "/";
		}
		// examine parent path
		final File file = new File(parent_path);
		if (file.exists()) {
			if (file.isDirectory()) {
				// all OK
				this.dir_mipmaps = parent_path + "trakem2." + unuid + "/trakem2.mipmaps/";
				try {
					File f = new File(this.dir_mipmaps);
					f.mkdirs();
					if (!f.exists()) {
						Utils.log("Could not create trakem2.mipmaps!");
						return false;
					}
				} catch (Exception e) {
					IJError.print(e);
					return false;
				}
			} else {
				Utils.showMessage("Selected parent path is not a directory. Please choose another one.");
				return createMipMapsDir(null);
			}
		} else {
			Utils.showMessage("Parent path does not exist. Please select a new one.");
			return createMipMapsDir(null);
		}
		return true;
	}

	/** Remove all mipmap images from the cache, and optionally set the dir_mipmaps to null. */
	public void flushMipMaps(boolean forget_dir_mipmaps) {
		if (null == dir_mipmaps) return;
		synchronized (db_lock) {
			try {
				if (forget_dir_mipmaps) this.dir_mipmaps = null;
				mawts.removeAndFlushAll();
			} catch (Throwable t) {
				handleCacheError(t);
			}
		}
	}

	/** Remove from the cache all images of level larger than zero corresponding to the given patch id. */
	public void flushMipMaps(final long id) {
		if (null == dir_mipmaps) return;
		synchronized (db_lock) {
			try {
				mawts.removeAndFlushPyramid(id);
			} catch (Throwable t) {
				handleCacheError(t);
			}
		}
	}

	/** Gets data from the Patch and queues a new task to do the file removal in a separate task manager thread. */
	public Future<Boolean> removeMipMaps(final Patch p) {
		return removeMipMaps(p, mExt);
	}

	private Future<Boolean> removeMipMaps(final Patch p, final String extension) {
		if (null == dir_mipmaps) return null;
		// cache values before they are changed:
		final int width = (int)p.getWidth();
		final int height = (int)p.getHeight();
		return remover.submit(new Callable<Boolean>() {
			public Boolean call() {
				try {
					final String path = getAbsolutePath(p);
					if (null == path) {
						// missing file
						Utils.log2("Remover: null path for Patch " + p);
						return false;
					}
					removeMipMaps(createIdPath(Long.toString(p.getId()), new File(path).getName(), extension), width, height);
					flushMipMaps(p.getId());
					return true;
				} catch (Exception e) {
					IJError.print(e);
				}
				return false;
			}
		});
	}

	private void removeMipMaps(final String filename, final int width, final int height) {
		int w = width;
		int h = height;
		int k = 0; // the level
		do {
			final File f = new File(new StringBuilder(dir_mipmaps).append(k).append('/').append(filename).toString());
			if (f.exists()) {
				try {
					if (!f.delete()) {
						Utils.log2("Could not remove file " + f.getAbsolutePath());
					}
				} catch (Exception e) {
					IJError.print(e);
				}
			}
			w /= 2;
			h /= 2;
			k++;
		} while (w >= 32 && h >= 32); // not smaller than 32x32
	}

	@Override
	public boolean usesMipMapsFolder() {
		return null != dir_mipmaps;
	}

	/** Return the closest level to @param level that exists as a file.
	 *  If no valid path is found for the patch, returns ERROR_PATH_NOT_FOUND.
	 */
	@Override
	public int getClosestMipMapLevel(final Patch patch, int level, final int max_level) {
		if (null == dir_mipmaps) return 0;
		try {
			final String path = getAbsolutePath(patch);
			if (null == path) return ERROR_PATH_NOT_FOUND;
			final String filename = new File(path).getName() + mExt;
			if (isURL(dir_mipmaps)) {
				if (level <= 0) return 0;
				// choose the smallest dimension
				// find max level that keeps dim over 32 pixels
				if (level > max_level) return max_level;
				return level;
			} else {
				do {
					final File f = new File(new StringBuilder(dir_mipmaps).append(level).append('/').append(filename).toString());
					if (f.exists()) {
						return level;
					}
					// try the next level
					level--;
				} while (level >= 0);
			}
		} catch (Exception e) {
			IJError.print(e);
		}
		return 0;
	}

	/** A temporary list of Patch instances for which a pyramid is being generated.
	 *  Access is synchronized by gm_lock. */
	final private Map<Patch,Future<Boolean>> regenerating_mipmaps = new HashMap<Patch,Future<Boolean>>();

	/** A lock for the generation of mipmaps. */
	final private Object gm_lock = new Object();

	/** Checks if the mipmap file for the Patch and closest upper level to the desired magnification exists. */
	public boolean checkMipMapFileExists(final Patch p, final double magnification) {
		if (null == dir_mipmaps) return false;
		final int level = getMipMapLevel(magnification, maxDim(p));
		if (isURL(dir_mipmaps)) return true; // just assume that it does
		if (new File(dir_mipmaps + level + "/" + new File(getAbsolutePath(p)).getName() + "." + p.getId() + mExt).exists()) return true;
		return false;
	}

	final Set<Patch> cannot_regenerate = Collections.synchronizedSet(new HashSet<Patch>());

	/** Loads the file containing the scaled image corresponding to the given level
	 *  (or the maximum possible level, if too large)
	 *  and returns it as an awt.Image, or null if not found.
	 *  Will also regenerate the mipmaps, i.e. recreate the pre-scaled jpeg images if they are missing.
	 *  Does NOT release memory, avoiding locking on the db_lock. */
	protected MipMapImage fetchMipMapAWT(final Patch patch, final int level, final long n_bytes) {
		return fetchMipMapAWT(patch, level, n_bytes, 0);
	}

	/** Does the actual fetching of the file. Returns null if the file does not exist.
	 *  Does NOT pre-release memory from the cache;
	 *  call releaseToFit to do that. */
	public final MipMapImage fetchMipMap(final Patch patch, int level, final long n_bytes) {
		final int max_level = getHighestMipMapLevel(patch);
		if ( level > max_level ) level = max_level;
		final double scale = Math.pow( 2.0, level );

		final String filename = getInternalFileName(patch);
		if (null == filename) {
			Utils.log2("null internal filename!");
			return null;
		}

		// New style:
		final String path = new StringBuilder(dir_mipmaps).append(  level ).append('/').append(createIdPath(Long.toString(patch.getId()), filename, mExt)).toString();

		//releaseToFit(n_bytes * 8); // eight times, for the jpeg decoder alloc/dealloc at least 2 copies, and with alpha even one more
		// TODO the x8 is overly exaggerated
		
		if ( patch.hasAlphaChannel() ) {
			final Image img = mmio.open( path );
			return img == null ? null : new MipMapImage( img, scale, scale );
		} else if ( patch.paintsWithFalseColor() ) {
			// AKA Patch has a LUT or is LUT image like a GIF
			final Image img = mmio.open( path );
			return img == null ? null : new MipMapImage( img, scale, scale ); // considers c_alphas
		} else {
			final Image img;
			switch (patch.getType()) {
				case ImagePlus.GRAY16:
				case ImagePlus.GRAY8:
				case ImagePlus.GRAY32:
					img = mmio.openGrey( path ); // ImageSaver.openGreyJpeg(path);
					return img == null ? null : new MipMapImage( img, scale, scale );
				default:
					// For color images: (considers URL as well)
					img = mmio.open( path );
					return img == null ? null : new MipMapImage( img, scale, scale ); // considers c_alphas
			}
		}
	}

	/** Will NOT free memory. */
	private final MipMapImage fetchMipMapAWT(final Patch patch, final int level, final long n_bytes, final int retries) {
		if (null == dir_mipmaps) {
			Utils.log2("null dir_mipmaps");
			return null;
		}
		while (retries < MAX_RETRIES) {
			try {
				// TODO should wait if the file is currently being generated

				final MipMapImage mipMap = fetchMipMap(patch, level, n_bytes);
				if (null != mipMap) return mipMap;

				// if we got so far ... try to regenerate the mipmaps
				if (!mipmaps_regen) {
					return null;
				}

				// check that REALLY the file doesn't exist.
				if (cannot_regenerate.contains(patch)) {
					Utils.log("Cannot regenerate mipmaps for patch " + patch);
					return null;
				}

				//Utils.log2("getMipMapAwt: imp is " + imp + " for path " +  dir_mipmaps + level + "/" + new File(getAbsolutePath(patch)).getName() + "." + patch.getId() + mExt);

				// Regenerate in the case of not asking for an image under 32x32
				double scale = 1 / Math.pow(2, level);
				if (level >= 0 && patch.getWidth() * scale >= 32 && patch.getHeight() * scale >= 32 && isMipMapsRegenerationEnabled()) {
					// regenerate in a separate thread
					regenerateMipMaps( patch );
					return new MipMapImage( REGENERATING, patch.getWidth() / REGENERATING.getWidth(), patch.getHeight() / REGENERATING.getHeight() );
				}
			} catch (OutOfMemoryError oome) {
				Utils.log2("fetchMipMapAWT: recovering from OutOfMemoryError");
				recoverOOME();
				Thread.yield();
				// Retry:
				return fetchMipMapAWT(patch, level, n_bytes, retries + 1);
			} catch (Throwable t) {
				IJError.print(t);
			}
		}
		return null;
	}

	static private AtomicInteger n_regenerating = new AtomicInteger(0);
	static private ExecutorService regenerator = null;
	static private ExecutorService remover = null;
	static public ExecutorService repainter = null;
	static private int nStaticServiceThreads = nStaticServiceThreads();
	static public ScheduledExecutorService autosaver = null;

	static private final class DONE implements Future<Boolean>
	{
		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return true;
		}
		@Override
		public Boolean get() throws InterruptedException, ExecutionException {
			return true;
		}
		@Override
		public Boolean get(long timeout, TimeUnit unit)
				throws InterruptedException, ExecutionException,
				TimeoutException {
			return true;
		}
		@Override
		public boolean isCancelled() {
			return false;
		}
		@Override
		public boolean isDone() {
			return true;
		}
	};
	
	/** Queue the regeneration of mipmaps for the Patch; returns immediately, having submitted the job to an executor queue;
	 *  returns a Future if the task was submitted, null if not. */
	@Override
	public final Future<Boolean> regenerateMipMaps(final Patch patch) {

		if (!isMipMapsRegenerationEnabled()) {
			// If not enabled, the cache must be flushed
			flushMipMaps(patch.getId());
			return new DONE();
		}

		synchronized (gm_lock) {
			try {
				Future<Boolean> fu = regenerating_mipmaps.get(patch);
				if (null != fu) return fu;

				// else, start it

				n_regenerating.incrementAndGet();
				Utils.log2("SUBMITTING to regen " + patch);
				Utils.showStatus(new StringBuilder("Regenerating mipmaps (").append(n_regenerating.get()).append(" to go)").toString());

				// Eliminate existing mipmaps, if any, in a separate thread:
				//Utils.log2("calling removeMipMaps from regenerateMipMaps");
				final Future<Boolean> removing = removeMipMaps(patch);

				fu = regenerator.submit(new Callable<Boolean>() {
					public Boolean call() {
						boolean b = false;
						try {
							// synchronize with the removal:
							if (null != removing) removing.get();
							Utils.showStatus(new StringBuilder("Regenerating mipmaps (").append(n_regenerating.get()).append(" to go)").toString());
							b = generateMipMaps(patch); // will remove the Future from the regenerating_mipmaps table, under proper gm_lock synchronization
							Display.repaint(patch.getLayer());
							Display.updatePanel(patch.getLayer(), patch);
							Utils.showStatus("");
						} catch (Exception e) {
							IJError.print(e);
						}
						n_regenerating.decrementAndGet();
						return b;
					}
				});

				regenerating_mipmaps.put(patch, fu);

				return fu;

			} catch (Exception e) {
				IJError.print(e);
				return null;
			}
		}
	}

	/** Compute the number of bytes that the ImagePlus of a Patch will take. Assumes a large header of 1024 bytes. If the image is saved as a grayscale jpeg the returned bytes will be 5 times as expected, because jpeg images are opened as int[] and then copied to a byte[] if all channels have the same values for all pixels. */ // The header is unnecessary because it's read, but not stored except for some of its variables; it works here as a safety buffer space.
	public long estimateImageFileSize(final Patch p, final int level) {
		if (level > 0) {
			// jpeg image to be loaded:
			final double scale = 1 / Math.pow(2, level);
			return (long)(p.getWidth() * scale * p.getHeight() * scale * 5 + 1024);
		}
		long size = (long)(p.getWidth() * p.getHeight());
		int bytes_per_pixel = 1;
		final int type = p.getType();
		switch (type) {
			case ImagePlus.GRAY32:
				bytes_per_pixel = 5; // 4 for the FloatProcessor, and 1 for the pixels8 to make an image
				break;
			case ImagePlus.GRAY16:
				bytes_per_pixel = 3; // 2 for the ShortProcessor, and 1 for the pixels8
			case ImagePlus.COLOR_RGB:
				bytes_per_pixel = 4;
				break;
			case ImagePlus.GRAY8:
			case ImagePlus.COLOR_256:
				bytes_per_pixel = 1;
				// check jpeg, which can only encode RGB (taken care of above) and 8-bit and 8-bit color images:
				String path = ht_paths.get(p.getId());
				if (null != path && path.endsWith(mExt)) bytes_per_pixel = 5; //4 for the int[] and 1 for the byte[]
				break;
			default:
				bytes_per_pixel = 5; // conservative
				break;
		}

		return size * bytes_per_pixel + 1024;
	}

	public String makeProjectName() {
		if (null == project_file_path || 0 == project_file_path.length()) return super.makeProjectName();
		final String name = new File(project_file_path).getName();
		final int i_dot = name.lastIndexOf('.');
		if (-1 == i_dot) return name;
		if (0 == i_dot) return super.makeProjectName();
		return name.substring(0, i_dot);
	}


	/** Returns the path where the imp is saved to: the storage folder plus a name. */
	public String handlePathlessImage(final ImagePlus imp) {
		FileInfo fi = imp.getOriginalFileInfo();
		if (null == fi) fi = imp.getFileInfo();
		if (null == fi.fileName || fi.fileName.equals("")) {
			fi.fileName = "img_" + System.currentTimeMillis() + ".tif";
		}
		if (!fi.fileName.endsWith(".tif")) fi.fileName += ".tif";
		fi.directory = dir_storage;
		if (imp.getNSlices() > 1) {
			new FileSaver(imp).saveAsTiffStack(dir_storage + fi.fileName);
		} else {
			new FileSaver(imp).saveAsTiff(dir_storage + fi.fileName);
		}
		Utils.log2("Saved a copy into the storage folder:\n" + dir_storage + fi.fileName);
		return dir_storage + fi.fileName;
	}

	/** Convert old-style storage folders to new style. */
	public boolean fixStorageFolders() {
		try {
			// 1 - Create folder unuid_folder at storage_folder + unuid
			if (null == this.unuid) {
				Utils.log2("No unuid for project!");
				return false;
			}
			// the trakem2.<unuid> folder that will now contain trakem2.mipmaps, trakem2.masks, etc.
			final String unuid_folder = getUNUIdFolder();
			File fdir = new File(unuid_folder);
			if (!fdir.exists()) {
				if (!fdir.mkdir()) {
					Utils.log2("Could not create folder " + unuid_folder);
					return false;
				}
			}
			// 2 - Create trakem2.mipmaps inside unuid folder
			final String new_dir_mipmaps = unuid_folder + "trakem2.mipmaps/";
			fdir = new File(new_dir_mipmaps);
			if (!fdir.mkdir()) {
				Utils.log2("Could not create folder " + new_dir_mipmaps);
				return false;
			}
			// 3 - Reorganize current mipmaps folder to folders with following convention: <level>/dd/dd/d.jpg where ddddd is Patch.id=12345 12/34/5.jpg etc.
			final String dir_mipmaps = getMipMapsFolder();
			for (final String name : new File(dir_mipmaps).list()) {
				final String level_dir = new StringBuilder(dir_mipmaps).append(name).append('/').toString();
				final File f = new File(level_dir);
				if (!f.isDirectory() || f.isHidden()) continue;
				for (final String mm : f.list()) {
					if (!mm.endsWith(mExt)) continue;
					// parse the mipmap file: filename + '.' + id + '.jpg'
					int last_dot = mm.lastIndexOf('.');
					if (-1 == last_dot) continue;
					int prev_last_dot = mm.lastIndexOf('.', last_dot -1);
					String id = mm.substring(prev_last_dot+1, last_dot);
					String filename = mm.substring(0, prev_last_dot);
					File oldf = new File(level_dir + mm);
					File newf = new File(new StringBuilder(new_dir_mipmaps).append(name).append('/').append(createIdPath(id, filename, mExt)).toString());
					File fd = newf.getParentFile();
					fd.mkdirs();
					if (!fd.exists()) {
						Utils.log2("Could not create parent dir " + fd.getAbsolutePath());
						continue;
					}
					if (!oldf.renameTo(newf)) {
						Utils.log2("Could not move mipmap file " + oldf.getAbsolutePath() + " to " + newf.getAbsolutePath());
						continue;
					}
				}
			}
			// Set it!
			this.dir_mipmaps = new_dir_mipmaps;

			// Remove old empty dirs:
			Utils.removeFile(new File(dir_mipmaps));

			// 4 - same for alpha folder and features folder.
			final String masks_folder = getStorageFolder() + "trakem2.masks/";
			File fmasks = new File(masks_folder);
			this.dir_masks = null;
			if (fmasks.exists()) {
				final String new_dir_masks = unuid_folder + "trakem2.masks/";
				final File[] fmask_files = fmasks.listFiles();
				if (null != fmask_files) { // can be null if there are no files inside fmask directory
					for (final File fmask : fmask_files) {
						final String name = fmask.getName();
						if (!name.endsWith(".zip")) continue;
						int last_dot = name.lastIndexOf('.');
						if (-1 == last_dot) continue;
						int prev_last_dot = name.lastIndexOf('.', last_dot -1);
						String id = name.substring(prev_last_dot+1, last_dot);
						String filename = name.substring(0, prev_last_dot);
						File newf = new File(new_dir_masks + createIdPath(id, filename, ".zip"));
						File fd = newf.getParentFile();
						fd.mkdirs();
						if (!fd.exists()) {
							Utils.log2("Could not create parent dir " + fd.getAbsolutePath());
							continue;
						}
						if (!fmask.renameTo(newf)) {
							Utils.log2("Could not move mask file " + fmask.getAbsolutePath() + " to " + newf.getAbsolutePath());
							continue;
						}
					}
				}
				// Set it!
				this.dir_masks = new_dir_masks;

				// remove old empty:
				Utils.removeFile(fmasks);
			}

			// TODO should save the .xml file, so the unuid and the new storage folders are set in there!

			return true;
		} catch (Exception e) {
			IJError.print(e);
		}
		return false;
	}

	/** For Patch id=12345 creates 12/34/5.${filename}.jpg */
	static public final String createMipMapRelPath(final Patch p, final String ext) {
		return createIdPath(Long.toString(p.getId()), new File(p.getCurrentPath()).getName(), ext);
	}

	/** For sid=12345 creates 12/34/5.${filename}.jpg
	 *  Will be fine with other filename-valid chars in sid. */
	static public final String createIdPath(final String sid, final String filename, final String ext) {
		final StringBuilder sf = new StringBuilder(((sid.length() * 3) / 2) + 1);
		final int len = sid.length();
		for (int i=1; i<=len; i++) {
			sf.append(sid.charAt(i-1));
			if (0 == i % 2 && len != i) sf.append('/');
		}
		return sf.append('.').append(filename).append(ext).toString();
	}

	public String getUNUId() {
		return unuid;
	}


	/** Waits until a proper image of the desired size or larger can be returned, which is never the Loader.REGENERATING image.
	 *  If no image can be loaded, returns Loader.NOT_FOUND.
	 *  If the Patch is undergoing mipmap regeneration, it waits until done.
	 */
	@Override
	public MipMapImage fetchDataImage( final Patch p, final double mag) {
		Future<Boolean> fu = null;
		MipMapImage mipMap = null;
		synchronized (gm_lock) {
			fu = regenerating_mipmaps.get(p);
		}
		if (null == fu) {
			// Patch is currently not under regeneration
			mipMap = fetchImage( p, mag );
			// If the patch mipmaps didn't exist,
			// the call to fetchImage will trigger mipmap regeneration
			// and img will be now Loader.REGENERATING
			if (Loader.REGENERATING != mipMap.image ) {
				return mipMap;
			} else {
				synchronized (gm_lock) {
					fu = regenerating_mipmaps.get(p);
				}
			}
		}
		if (null != fu) {
			try {
				if ( ! fu.get()) {
					Utils.log("Loader.fetchDataImage: could not regenerate mipmaps and get an image for patch " + p);
					return new MipMapImage( NOT_FOUND, p.getWidth() / NOT_FOUND.getWidth(), p.getHeight() / NOT_FOUND.getHeight() );
				}
				// Now the image should be good:
				mipMap = fetchImage(p, mag);
				
				// Check in any case:
				if (Loader.isSignalImage(mipMap.image)) {
					// Attempt to create from scratch
					return new MipMapImage( p.createTransformedImage().createImage(p.getMin(), p.getMax()), 1, 1);
				} else {
					return mipMap;
				}
				
			} catch (Throwable e) {
				IJError.print(e);
			}
		}

		// else:
		Utils.log( "Loader.fetchDataImage: could not get a data image for patch " + p );
		return new MipMapImage( NOT_FOUND, p.getWidth() / NOT_FOUND.getWidth(), p.getHeight() / NOT_FOUND.getHeight() );
	}

	
	public ImagePlus fetchImagePlus( Stack stack )
	{
		ImagePlus imp = null;
		String path = null;
		ImageLoadingLock plock = null;
		synchronized (db_lock) {
			try {
				imp = mawts.get(stack.getId());
				if (null != imp) {
					return imp;
				}
				path = stack.getFilePath();
				/* not cached */
				plock = getOrMakeImageLoadingLock( stack.getId(), 0 );
			} catch (Throwable t) {
				handleCacheError(t);
				return null;
			}
		}


		synchronized (plock) {
			imp = mawts.get( stack.getId());
			if (null != imp) {
				// was loaded by a different thread
				synchronized (db_lock) {
					removeImageLoadingLock(plock);
				}
				return imp;
			}

			// going to load:
			releaseToFit(stack.estimateImageFileSize());
			imp = openImage(getAbsolutePath(path));

			//preProcess(p, imp);


			synchronized (db_lock) {
				try {
					if (null == imp) {
						if (!hs_unloadable.contains(stack)) {
							Utils.log("FSLoader.fetchImagePlus: no image exists for stack  " + stack + "  at path " + path);
							hs_unloadable.add( stack );
						}
//						if (ControlWindow.isGUIEnabled()) {
//							/* TODO offer repair for more things than patches */
//							FilePathRepair.add( stack );
//						}
						return null;
					} else {
						mawts.put( stack.getId(), imp, (int)Math.max(stack.getWidth(), stack.getHeight()));
					}

				} catch (Exception e) {
					IJError.print(e);
				} finally {
					removeImageLoadingLock(plock);
				}

				return imp;
			}
		}
	}

	/**
	 * Delete stale files under the {@link FSLoader#unuid} folder.
	 * These include "*.ct" files (for {@link CoordinateTransform})
	 * and "*.zip" files (for alpha mask images) that are not referenced from any {@link Patch}.
	 */
	@Override
	public boolean deleteStaleFiles(boolean coordinate_transforms, boolean alpha_masks) {
		boolean b = true;
		final Project project = Project.findProject(this);
		if (coordinate_transforms) b = b && StaleFiles.deleteCoordinateTransforms(project);
		if (alpha_masks) b = b && StaleFiles.deleteAlphaMasks(project);
		return b;
	}


	////////////////////


	static final public String[] MIPMAP_FORMATS = new String[]{".jpg", ".png", ".tif", ".raw", ".rag"};
	static public final int MIPMAP_JPEG = 0;
	static public final int MIPMAP_PNG = 1;
	static public final int MIPMAP_TIFF = 2;
	static public final int MIPMAP_RAW = 3;
	static public final int MIPMAP_RAG = 4;

	static private final int MIPMAP_HIGHEST = MIPMAP_RAG; // WARNING: update this value if other formats are added

	// Default: RAG
	private int mipmaps_format = MIPMAP_RAG;
	private String mExt = MIPMAP_FORMATS[mipmaps_format]; // the extension currently in use
	private RWImage mmio = new RWImageRag();

	private RWImage newMipMapRWImage() {
		switch (this.mipmaps_format) {
			case MIPMAP_JPEG:
				return new RWImageJPG();
			case MIPMAP_PNG:
				return new RWImagePNG();
			case MIPMAP_TIFF:
				return new RWImageTIFF();
			case MIPMAP_RAW:
				return new RWImageRaw();
			case MIPMAP_RAG:
				return new RWImageRag();
			// WARNING add here another one
		}
		return null;
	}

	/** Any of: {@link #MIPMAP_JPEG}, {@link #MIPMAP_PNG}, {@link #MIPMAP_TIFF}, {@link #MIPMAP_RAW},
	 * {@link #MIPMAP_RAG}. */
	@Override
	public final int getMipMapFormat() {
		return mipmaps_format;
	}

	@Override
	public final boolean setMipMapFormat(final int format) {
		switch (format) {
			case MIPMAP_JPEG:
			case MIPMAP_PNG:
			case MIPMAP_TIFF:
			case MIPMAP_RAW:
			case MIPMAP_RAG:
				this.mipmaps_format = format;
				this.mExt = MIPMAP_FORMATS[mipmaps_format];
				this.mmio = newMipMapRWImage();
				return true;
			default:
				Utils.log("Ignoring unknown mipmap format: " + format);
				return false;
		}
	}

	/** Removes all mipmap files and recreates them with the currently set mipmaps format.
	 *  @param old_format Any of MIPMAP_JPEG, MIPMAP_PNG in which files were saved before. */
	@Override
	public Bureaucrat updateMipMapsFormat(final int old_format, final int new_format) {
		if (old_format < 0 || old_format > MIPMAP_HIGHEST) {
			Utils.log("Invalid old format for mipmaps!");
			return null;
		}
		if (!setMipMapFormat(new_format)) {
			Utils.log("Invalid new format for mipmaps!");
			return null;
		}
		final Project project = Project.findProject(FSLoader.this);
		return Bureaucrat.createAndStart(new Worker.Task("Updating mipmaps format") {
			public void exec() {
				try {
					final List<Future<?>> fus = new ArrayList<Future<?>>();
					final String ext = MIPMAP_FORMATS[old_format];
					for (Layer la : project.getRootLayerSet().getLayers()) {
						for (Displayable p : la.getDisplayables(Patch.class)) {
							fus.add(removeMipMaps((Patch)p, ext));
						}
					}
					Utils.wait(fus);
					fus.clear();
					for (Layer la : project.getRootLayerSet().getLayers()) {
						for (Displayable p : la.getDisplayables(Patch.class)) {
							fus.add(regenerateMipMaps((Patch)p));
						}
					}
					Utils.wait(fus);
				} catch (Exception e) {
					IJError.print(e);
				}
			}
		}, project);
	}

	private abstract class RWImage {
		boolean save(ImageProcessor ip, final String path, final float quality, final boolean as_grey) {
			if (as_grey) ip = ip.convertToByte(false);
			if (ip instanceof ByteProcessor) {
				return save(path, new byte[][]{(byte[])ip.getPixels()}, ip.getWidth(), ip.getHeight(), quality);
			} else if (ip instanceof ColorProcessor) {
				final int[] p = (int[]) ip.getPixels();
				final byte[] r = new byte[p.length],
				             g = new byte[p.length],
				             b = new byte[p.length],
				             a = new byte[p.length];
				for (int i=0; i<p.length; ++i) {
					final int x = p[i];
					r[i] = (byte)((x >> 16)&0xff);
					g[i] = (byte)((x >>  8)&0xff);
					b[i] = (byte) (x       &0xff);
					a[i] = (byte)((x >> 24)&0xff);
				}
				return save(path, new byte[][]{r, g, b, a}, ip.getWidth(), ip.getHeight(), quality);
			}
			return false;
		}
		boolean save(final BufferedImage bi, final String path, final float quality, final boolean as_grey) {
			switch (bi.getType()) {
				case BufferedImage.TYPE_BYTE_GRAY:
					return save(new ByteProcessor(bi), path, quality, false);
				default:
					if (as_grey) return save(new ByteProcessor(bi), path, quality, false);
					return save(new ColorProcessor(bi), path, quality, false);
			}
		}
		abstract boolean save(String path, byte[][] b, int width, int height, float quality);
		/** Opens grey, RGB and RGBA. */
		abstract BufferedImage open(String path);
		/** Opens grey images or, if not grey, converts them to grey. */
		abstract BufferedImage openGrey(String path);
	}
	private final class RWImageJPG extends RWImage {
		@Override
		final boolean save(final ImageProcessor ip, final String path, final float quality, final boolean as_grey) {
			return ImageSaver.saveAsJpeg(ip, path, quality, as_grey);
		}
		@Override
		final boolean save(final BufferedImage bi, final String path, final float quality, final boolean as_grey) {
			return ImageSaver.saveAsJpeg(bi, path, quality, as_grey);
		}
		@Override
		final BufferedImage open(String path) {
			return ImageSaver.openImage(path, true);
		}
		@Override
		final BufferedImage openGrey(final String path) {
			return ImageSaver.open(path, true);
		}
		@Override
		final boolean save(final String path, final byte[][] b, final int width, final int height, final float quality) {
			switch (b.length) {
				case 1:
					return ImageSaver.saveAsGreyJpeg(b[0], width, height, path, quality);
				case 2:
					return ImageSaver.saveAsJpegAlpha(ImageSaver.createARGBImage(P.blend(b[0], b[1]), width, height), path, quality);
				case 3:
					return ImageSaver.saveAsJpeg(ImageSaver.createRGBImage(P.blend(b[0], b[1], b[2]), width, height), path, quality, false);
				case 4:
					return ImageSaver.saveAsJpegAlpha(ImageSaver.createARGBImage(P.blend(b[0], b[1], b[2], b[3]), width, height), path, quality);
			}
			return false;
		}
	}
	private final class RWImagePNG extends RWImage {
		@Override
		final boolean save(final ImageProcessor ip, final String path, final float quality, final boolean as_grey) {
			return ImageSaver.saveAsPNG(ip, path);
		}
		@Override
		final boolean save(final BufferedImage bi, final String path, final float quality, final boolean as_grey) {
			return ImageSaver.saveAsPNG(bi, path);
		}
		@Override
		final BufferedImage open(String path) {
			return ImageSaver.openImage(path, true);
		}
		@Override
		final BufferedImage openGrey(final String path) {
			return ImageSaver.openGreyImage(path);
		}
		@Override
		final boolean save(final String path, final byte[][] b, final int width, final int height, final float quality) {
			BufferedImage bi = null;
			try {
				switch (b.length) {
					case 1:
						bi = ImageSaver.createGrayImage(b[0], width, height);
						return ImageSaver.saveAsPNG(bi, path);
					case 2:
						bi = ImageSaver.createARGBImage(P.blend(b[0], b[1]), width, height);
						return ImageSaver.saveAsPNG(bi, path);
					case 3:
						bi = ImageSaver.createRGBImage(P.blend(b[0], b[1], b[2]), width, height);
						return ImageSaver.saveAsPNG(bi, path);
					case 4:
						bi = ImageSaver.createARGBImage(P.blend(b[0], b[1], b[2], b[3]), width, height);
						return ImageSaver.saveAsPNG(bi, path);
				}
			} finally {
				if (null != bi) {
					bi.flush();
					CachingThread.storeArrayForReuse(bi);
				}
			}
			return false;
		}
	}
	private final class RWImageTIFF extends RWImage {
		@Override
		final boolean save(final ImageProcessor ip, final String path, final float quality, final boolean as_grey) {
			return ImageSaver.saveAsTIFF(ip, path, as_grey);
		}
		@Override
		final boolean save(final BufferedImage bi, final String path, final float quality, final boolean as_grey) {
			return ImageSaver.saveAsTIFF(bi, path, as_grey);
		}
		@Override
		final BufferedImage openGrey(final String path) {
			return ImageSaver.openGreyTIFF(path);
		}
		@Override
		final BufferedImage open(String path) {
			return ImageSaver.openTIFF(path, true);
		}
		@Override
		final boolean save(final String path, final byte[][] b, final int width, final int height, final float quality) {
			switch (b.length) {
				case 1:
					return ImageSaver.saveAsTIFF(ImageSaver.createGrayImage(b[0], width, height), path, false); // already grey
				case 2:
					return ImageSaver.saveAsTIFF(ImageSaver.createARGBImage(P.blend(b[0], b[1]), width, height), path, false);
				case 3:
					return ImageSaver.saveAsTIFF(ImageSaver.createRGBImage(P.blend(b[0], b[1], b[2]), width, height), path, false);
				case 4:
					return ImageSaver.saveAsTIFF(ImageSaver.createARGBImage(P.blend(b[0], b[1], b[2], b[3]), width, height), path, false);
			}
			return false;
		}
	}
	private final class RWImageRaw extends RWImage {
		@Override
		final BufferedImage open(final String path) {
			return RawMipMaps.read(path);
		}
		@Override
		final BufferedImage openGrey(final String path) {
			return ImageSaver.asGrey(RawMipMaps.read(path)); // TODO may not need the asGrey if all is correct
		}
		@Override
		final boolean save(final String path, final byte[][] b, final int width, final int height, final float quality) {
			try {
				return RawMipMaps.save(path, b, width, height);
			} finally {
				CachingThread.storeForReuse(b);
			}
		}
	}
	private final class RWImageRag extends RWImage {
		@Override
		final BufferedImage open(final String path) {
			return RagMipMaps.read(path);
		}
		@Override
		final BufferedImage openGrey(final String path) {
			return ImageSaver.asGrey(RagMipMaps.read(path)); // TODO may not need the asGrey if all is correct
		}
		@Override
		final boolean save(final String path, final byte[][] b, final int width, final int height, final float quality) {
			try {
				return RagMipMaps.save(path, b, width, height);
			} finally {
				CachingThread.storeForReuse(b);
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	@Override
	protected boolean mapIntensities(final Patch p, final ImagePlus imp) {
		
		final ImagePlus coefficients = new Opener().openImage(
			getUNUIdFolder() +
			"trakem2.its/" +
			createIdPath(Long.toString(p.getId()), "it", ".tif"));

		if (coefficients == null)
			return false;
		
		final ImageProcessor ip = imp.getProcessor();
		
		@SuppressWarnings({"rawtypes"})
		final LinearIntensityMap<FloatType> map =
				new LinearIntensityMap<FloatType>(
						(FloatImagePlus)ImagePlusImgs.from(coefficients));

		@SuppressWarnings("rawtypes")
		Img img;

		final long[] dims = new long[]{imp.getWidth(), imp.getHeight()};
		switch (p.getType()) {
		case ImagePlus.GRAY8:
		case ImagePlus.COLOR_256:		// this only works for continuous color tables
			img = ArrayImgs.unsignedBytes((byte[])ip.getPixels(), dims);
			break;
		case ImagePlus.GRAY16:
			img = ArrayImgs.unsignedShorts((short[])ip.getPixels(), dims);
			break;
		case ImagePlus.COLOR_RGB:
			img = ArrayImgs.argbs((int[])ip.getPixels(), dims);
			break;
		case ImagePlus.GRAY32:
			img = ArrayImgs.floats((float[])ip.getPixels(), dims);
			break;
		default:
			img = null;
		}
		
		if (img == null)
			return false;

		map.run(img);
		
		return true;
	}
	
	@Override
	public boolean clearIntensityMap(final Patch p) {
		final File coefficients = new File(
				getUNUIdFolder() +
				"trakem2.its/" +
				createIdPath(Long.toString(p.getId()), "it", ".tif"));
		return coefficients.delete();
	}
}
