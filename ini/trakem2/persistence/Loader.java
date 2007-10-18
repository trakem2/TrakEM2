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

package ini.trakem2.persistence;

import ini.trakem2.utils.IJError;

/**
 * Database cleanup copy-paste:
drop table ab_attributes, ab_ball_points, ab_displayables, ab_displays, ab_labels, ab_layer_sets, ab_layers, ab_links, ab_patches, ab_pipe_points, ab_profiles, ab_projects, ab_things, ab_zdisplayables; drop sequence ab_ids;

 * 
 */
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.gui.YesNoCancelDialog;
import ij.io.DirectoryChooser;
import ij.io.FileInfo;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.io.OpenDialog;
import ij.io.TiffEncoder;
import ij.plugin.filter.PlugInFilter;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.StackProcessor;
import ij.process.StackStatistics;
import ij.process.ImageStatistics;
import ij.measure.Calibration;

import mpi.fruitfly.registration.PhaseCorrelation2D;

import ini.trakem2.Project;
import ini.trakem2.display.Ball;
import ini.trakem2.display.DLabel;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Pipe;
import ini.trakem2.display.Profile;
import ini.trakem2.display.Snapshot;
import ini.trakem2.display.YesNoDialog;
import ini.trakem2.display.ZDisplayable;
import ini.trakem2.tree.*;
import ini.trakem2.utils.*;
import ini.trakem2.io.*;
import ini.trakem2.imaging.*;
import ini.trakem2.ControlWindow;

import javax.swing.tree.*;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import java.awt.Color;
import java.awt.Component;
import java.awt.Checkbox;
import java.awt.Cursor;
//import java.awt.FileDialog;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.geom.Area;
import java.awt.geom.AffineTransform;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.zip.Inflater;
import java.util.zip.Deflater;

import javax.swing.JMenu;


/** Handle all data-related issues with a virtualization engine, including load/unload and saving, saving as and overwriting. */
abstract public class Loader {

	// Only one thread at a time is to use the connection and cache
	protected final Object db_lock = new Object();
	private boolean db_busy = false;

	protected Opener opener = new Opener();

	/** The cache is shared, and can be flagged to do massive flushing. */
	private boolean massive_mode = false;

	/** Keep track of whether there are any unsaved changes.*/
	protected boolean changes = false;

	static public final BufferedImage NOT_FOUND = new BufferedImage(10, 10, BufferedImage.TYPE_BYTE_BINARY);
	static {
		Graphics2D g = NOT_FOUND.createGraphics();
		g.setColor(Color.white);
		g.drawRect(1, 1, 8, 8);
		g.drawLine(3, 3, 7, 7);
		g.drawLine(7, 3, 3, 7);
	}

	// the cache: shared, for there is only one JVM! (we could open a second one, and store images there, and transfer them through sockets)


	// What I need is not provided: a LinkedHashMap with a method to do 'removeFirst' or remove(0) !!! To call my_map.entrySet().iterator() to delete the the first element of a LinkedHashMap is just too much calling for an operation that has to be blazing fast. So I create a double list setup with arrays. The variables are not static because each loader could be connected to a different database, and each database has its own set of unique ids. Memory from other loaders is free by the releaseOthers(double) method.
	protected FIFOImageMap awts = new FIFOImageMap(50);
	protected FIFOImagePlusMap imps = new FIFOImagePlusMap(50);
	protected FIFOImageMap snaps = new FIFOImageMap(50);

	static protected Vector v_loaders = null; // Vector: synchronized

	protected Loader() {
		// register
		if (null == v_loaders) v_loaders = new Vector();
		v_loaders.add(this);
		if (!ControlWindow.isGUIEnabled()) {
			opener.setSilentMode(true);
		}

		// debug: report cache status every ten seconds
		/*
		final Loader lo = this;
		new Thread() {
			public void run() {
				while (true) {
					try { Thread.sleep(10000); } catch (InterruptedException ie) {}
					synchronized(db_lock) {
						lock();
						if (!v_loaders.contains(lo)) {
							unlock();
							break;
						}
						Utils.log2("CACHE: \n\timps: " + imps.size() + "\n\tawts: " + awts.size() + "\n\tsnaps: " + snaps.size());
						unlock();
					}
				}
			}
		}.start();
		*/
	}

	abstract public boolean isReady();

	/** To be called within a synchronized(db_lock) */
	protected final void lock() {
		//Utils.printCaller(this, 7);
		while (db_busy) { try { db_lock.wait(); } catch (InterruptedException ie) {} }
		db_busy = true;
	}

	/** To be called within a synchronized(db_lock) */
	protected final void unlock() {
		//Utils.printCaller(this, 7);
		if (db_busy) {
			db_busy = false;
			db_lock.notifyAll();
		}
	}

	/** Release all memory and unregister itself. Child Loader classes should call this method in their destroy() methods. */
	synchronized public void destroy() {
		if (null != IJ.getInstance() && IJ.getInstance().quitting()) {
			return; // no need to do anything else
		}
		Utils.showStatus("Releasing all memory ...");
		releaseAll();
		if (null != v_loaders) {
			v_loaders.remove(this); // sync issues when deleting two loaders consecutively
			if (0 == v_loaders.size()) v_loaders = null;
		}
	}

	/**Retrieve next id from a sequence for a new DBObject to be added.*/
	abstract public long getNextId();

	/** Ask for the user to provide a template XML file to extract a root TemplateThing. */
	public TemplateThing askForXMLTemplate(Project project) {
		// ask for an .xml file or a .dtd file
		//fd.setFilenameFilter(new XMLFileFilter(XMLFileFilter.BOTH));
		String user = System.getProperty("user.name");
		OpenDialog od = new OpenDialog("Select XML Template",
						(user.equals("albertca") || user.equals("cardona")) ? "/home/" + user + "/temp" : OpenDialog.getDefaultDirectory(),
						null);
		String file = od.getFileName();
		if (null == file || file.toLowerCase().startsWith("null")) return null;
		// if there is a path, read it out if possible
		String path = od.getDirectory() + "/" + file;
		TemplateThing[] roots = DTDParser.extractTemplate(path);
		if (null == roots || roots.length < 1) return null;
		if (roots.length > 1) {
			Utils.showMessage("Found more than one root.\nUsing first root only.");
		}
		return roots[0];
	}

	private boolean temp_snapshots_enabled = true;

	public void startLargeUpdate() {
		LayerSet ls = Project.findProject(this).getRootLayerSet();
		temp_snapshots_enabled = ls.areSnapshotsEnabled();
		if (temp_snapshots_enabled) ls.setSnapshotsEnabled(false);
	}

	public void commitLargeUpdate() {
		Project.findProject(this).getRootLayerSet().setSnapshotsEnabled(temp_snapshots_enabled);
	}

	public void rollback() {
		Project.findProject(this).getRootLayerSet().setSnapshotsEnabled(temp_snapshots_enabled);
	}

	abstract public double[][][] fetchBezierArrays(long id);

	abstract public ArrayList fetchPipePoints(long id);

	abstract public ArrayList fetchBallPoints(long id);

	abstract public Area fetchArea(long area_list_id, long layer_id);

	/** Get the snapshot again from the database, unless it's on cache or the Patch is on cache (Remake from it). */
	abstract public Image fetchSnapshot(Patch p);


	/* GENERIC, from DBObject calls */
	abstract public boolean addToDatabase(DBObject ob);

	abstract public boolean updateInDatabase(DBObject ob, String key);

	abstract public boolean removeFromDatabase(DBObject ob);

	/* Reflection would be the best way to do all above; when it's about and 'id', one only would have to check whether the field in question is a BIGINT and the object given a DBObject, and call getId(). Such an approach demands, though, perfect matching of column names with class field names. */

	// for cache flushing
	public boolean getMassiveMode() {
		return massive_mode;
	}

	// for cache flushing
	public final void setMassiveMode(boolean m) {
		massive_mode = m;
		//Utils.log2("massive mode is " + m + " for loader " + this);
	}

	/** Retrieves a zipped ImagePlus from the given InputStream. The stream is not closed and must be closed elsewhere. No error checking is done as to whether the stream actually contains a zipped tiff file. */
	protected ImagePlus unzipTiff(InputStream i_stream, String title) {
		ImagePlus imp;
		try {
			// Reading a zipped tiff file in the database


			/* // works but not faster
			byte[] bytes = null;
			// new style: RAM only
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int len;
			int length = 0;
			while (true) {
				len = i_stream.read(buf);
				if (len<0) break;
				length += len;
				out.write(buf, 0, len);
			}
			Inflater infl = new Inflater();
			infl.setInput(out.toByteArray(), 0, length);
			int buflen = length + length;
			buf = new byte[buflen]; //short almost for sure
			int offset = 0;
			ArrayList al = new ArrayList();
			while (true) {
				len = infl.inflate(buf, offset, buf.length);
				al.add(buf);
				if (0 == infl.getRemaining()) break;
				buf = new byte[length*2];
				offset += len;
			}
			infl.end();
			byte[][] b = new byte[al.size()][];
			al.toArray(b);
			int blength = buflen * (b.length -1) + len; // the last may be shorter
			bytes = new byte[blength];
			for (int i=0; i<b.length -1; i++) {
				System.arraycopy(b[i], 0, bytes, i*buflen, buflen);
			}
			System.arraycopy(b[b.length-1], 0, bytes, buflen * (b.length-1), len);
			*/


			//OLD, creates tmp file (archive style)
			ZipInputStream zis = new ZipInputStream(i_stream);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096]; //copying savagely from ImageJ's Opener.openZip()
			ZipEntry entry = zis.getNextEntry(); // I suspect this is needed as an iterator
			int len;
			while (true) {
				len = zis.read(buf);
				if (len<0) break;
				out.write(buf, 0, len);
			}
			zis.close();
			byte[] bytes = out.toByteArray();

			ij.IJ.redirectErrorMessages();
			imp = opener.openTiff(new ByteArrayInputStream(bytes), title);
			// NO! Database images may get preprocessed everytime one opends them. The preprocessor is only intended to be applied to files opened from the file system. //preProcess(imp);

			//old
			//ij.IJ.redirectErrorMessages();
			//imp = new Opener().openTiff(i_stream, title);
		} catch (Exception e) {
			new IJError(e);
			return null;
		}
		return imp;
	}

	public void addCrossLink(long project_id, long id1, long id2) {}

	/** Remove a link between two objects. Returns true always in this empty method. */
	public boolean removeCrossLink(long id1, long id2) { return true; }

	synchronized public void cache(Displayable d) {
		cache(d, null);
		notify();
	}

	/** Add to the cache, or if already there, make it be the last (to be flushed the last). */
	public void cache(final Displayable d, final ImagePlus imp) {
		synchronized (db_lock) {
			lock();
			final long id = d.getId(); // each Displayable has a unique id for each database, not for different databases, that's why the cache is NOT shared.
			if (d instanceof Patch) {
				Patch p = (Patch)d;
				if (null == imps.get(id)) { // the 'get' call already puts it at the end if there.
					imps.put(id, imp);
				}
				if (!massive_mode) { // don't if loading lots of images
					if (null == awts.get(id)) {
						unlock();
						Image awt = p.createImage();
						lock();
						awts.put(id, awt);  // with adjusted channels. Will flush the Image if it existed and was different
					}
					if (null == snaps.get(id) && null != d.getLayer()) {
						final Image image = awts.get(id);
						unlock();
						final Image snap = Snapshot.createSnap(p, image, Snapshot.SCALE);
						lock();
						snaps.put(id, snap);
					}
				}
			} else {
				Utils.log("Loader.cache: don't know how to cache: " + d);
			}
			unlock();
		}
	}

	synchronized public void updateCache(Patch patch) {
		updateCache(patch, imps.get(patch.getId()));
	}

	/** Update the awt, replace the imp with the new imp, and remake the snapshot from the awt*/
	public void updateCache(Patch patch, ImagePlus imp) {
		synchronized (db_lock) {
			lock();
			imps.put(patch.getId(), imp); // replace with the new imp
			unlock();
		}
		patch.createImage(imp); // create from the cached new imp, and cache the new awt
		// will lock on its own
		patch.getSnapshot().remake(); // create from the cached new awt, and cache the new snap
	}

	public void cacheAWT(long id, Image image) {
		synchronized (db_lock) {
			lock();
			awts.put(id, image); // will flush the image if it exists in the cache, and add the new at the end
			unlock();
		}
	}

	public void cacheSnapshot(long id, Image snap) {
		synchronized (db_lock) {
			lock();
			snaps.put(id, snap);
			unlock();
		}
	}

	/** Cache any ImagePlus, as long as a unique id is assigned to it there won't be problems; you can obtain a unique id from method getNextId() .*/
	public void cacheImagePlus(long id, ImagePlus imp) {
		synchronized (db_lock) {
			lock();
			imps.put(id, imp); // TODO this looks totally unnecessary
			unlock();
		}
	}

	public void decacheImagePlus(long id) {
		synchronized (db_lock) {
			lock();
			ImagePlus imp = imps.remove(id);
			if (null != imp) imp.flush(); // this looks totally unnecessary
			unlock();
		}
	}

	public void decacheImagePlus(long[] id) {
		synchronized (db_lock) {
			lock();
			for (int i=0; i<id.length; i++) {
				ImagePlus imp = imps.remove(id[i]);
				if (null != imp) imp.flush();
			}
			unlock();
		}
	}

	public void updateCache(Displayable d, String key) {
		if (key.startsWith("points=")) {
			long lid = Long.parseLong(key.substring(7)); // for AreaList
			decacheImagePlus(lid);
		} else if (d instanceof ZDisplayable) {
			// remove all layers in which the ZDisplayable paints to
			ZDisplayable zd = (ZDisplayable)d;
			for (Iterator it = zd.getLayerSet().getLayers().iterator(); it.hasNext(); ) {
				Layer layer = (Layer)it.next();
				if (zd.paintsAt(layer)) decacheImagePlus(layer.getId());
			}
		} else {
			// remove the layer where the Displayable paints to
			if (null == d.getLayer()) {
				// Top Level LayerSet has no layer
				return;
			}
			decacheImagePlus(d.getLayer().getId());
		}
	}

	///////////////////

	static private final float OSFRACTION = computeOSFraction();
	static private final float computeOSFraction() {
		int bits = 32;
		try {
			// 32 or 64 bit?
			bits = Integer.parseInt(System.getProperty("sun.arch.data.model"));
		} catch (Exception e) {
			new IJError(e);
			return 0.68f; // conservative
		}
		if (64 == bits) return 0.68f; // 64-bit JVM
		return 0.80f; // 32-bit JVM
	}
	/** Really available maximum memory, in bytes.
	 *  By try and error I have found out that, at least in Linux:
	 *  * 64-bit systems have a real maximum of 68% of the Xmx maximum heap memory value.
	 *  * 32-bit systems have about 80% of the Xmx.
	 */
	static public final long max_memory = (long)((IJ.maxMemory() * OSFRACTION) - 3000000); // 3 M always free
	
	/** Measure wether there is at least 20% of available memory. */
	protected boolean enoughFreeMemory() {
		long mem_in_use = (IJ.currentMemory() * 100) / max_memory; // IJ.maxMemory();
		if (mem_in_use < 80L) { // 80 % // this 100-20 could very well be the actual lost-in-hyperspace memory, which may be 20% for 32-bit and 32% in 64-bit systems
			return true;
		}
		return false;
	}

	/** Measure whether there are at least 'bytes' free. */
	protected boolean enoughFreeMemory(long bytes) {
		//long max_memory = IJ.maxMemory() - 3000000L; // 3 Mb always free
		long mem_in_use = IJ.currentMemory(); // in bytes
		//Utils.log("max_memory: " + max_memory + "  mem_in_use: " + mem_in_use);
		if (bytes < max_memory - mem_in_use) {
			return true;
		} else {
			return false;
		}
	}

	public final void releaseToFit(final int width, final int height, final int type, float factor) {
		long bytes = width * height;
		switch (type) {
			case ImagePlus.GRAY32:
				bytes *= 5; // 4 for the FloatProcessor, and 1 for the generated pixels8 to make an image
				if (factor < 4) factor = 4; // for Open_MRC_Leginon ... TODO this is unnecessary in all other cases
				break;
			case ImagePlus.COLOR_RGB:
				bytes *= 4;
				break;
			case ImagePlus.GRAY16:
				bytes *= 3; // 2 for the ShortProcessor, and 1 for the pixels8
				break;
			default: // times 1
				break;
		}
		releaseToFit((long)(bytes*factor));
	}

	/** Release enough memory so that as many bytes as passed as argument can be loaded. */
	public final boolean releaseToFit(long bytes) {
		//long max_memory = IJ.maxMemory() - 3000000L; // 3 Mb always free
		if (bytes > max_memory) {
			Utils.showMessage("Can't fit " + bytes + " bytes in memory.");
			return false;
		}
		boolean previous = massive_mode;
		if (bytes > max_memory / 4) setMassiveMode(true);
		int iterations = 30;
		boolean result = true;
		synchronized (db_lock) {
			lock();
			while (!enoughFreeMemory(bytes)) {
				Utils.log2("rtf " + iterations);
				releaseMemory(0.5D, true, bytes);
				if (0 == imps.size() && 0 == awts.size() && 0 == snaps.size()) {
					// wait for GC ...
					try { Thread.sleep(1000); } catch (InterruptedException ie) {}
					// release offscreen images (will leave the canvas labeled for remaking when necessary)
					if (iterations < 20) Display.flushAll();
				}
				if (iterations < 0) {
					Utils.log("Can't make room for " + bytes + " bytes in memory.");
					result = false;
					break;
				}
				Thread.yield(); // for the GC to run
				try { Thread.sleep(500); } catch (InterruptedException ie) {}
				iterations--;
			}
			unlock();
		}
		setMassiveMode(previous);
		return result;
	}

	/** This method tries to cope with the lack of real time garbage collection in java (that is, lack of predictable time for memory release). */
	static public final void runGC() {
		//Utils.printCaller("runGC", 4);
		final long initial = IJ.currentMemory();
		long now = initial;
		final int max = 10;
		long sleep = 50; // initial value
		int iterations = 0;
		do {
			Runtime.getRuntime().runFinalization(); // enforce it
			System.gc();
			Thread.yield();
			try { Thread.sleep(sleep); } catch (InterruptedException ie) {}
			sleep += sleep; // incremental
			now = IJ.currentMemory();
			Utils.log("\titer " + iterations + "  initial: " + initial  + " now: " + now);
			iterations++;
		} while (now >= initial && iterations < max);
		Utils.log2("finished runGC");
	}

	/** The minimal number of memory bytes that should always be free. */
	public static final long MIN_FREE_BYTES = (long)(max_memory * 0.2f);

	/** Remove up to half the ImagePlus cache of others (but their awts and snaps first if needed) and then one single ImagePlus of this Loader's cache. */
	public final void releaseMemory() {
		releaseMemory(0.5D, true, MIN_FREE_BYTES);
	}

	/** Release as much of the cache as necessary to make 'enoughFreeMemory()'.
	*  The very last thing to remove is the stored awt.Image objects and then the snaps.
	*  Removes one ImagePlus at a time if a == 0, else up to 0 &lt; a &lt;= 1.0 .
	*/ // NOT locked, however calls must take care of that.
	public final void releaseMemory(final double a, final boolean release_others, final long min_free_bytes) {
		try {
			while (!enoughFreeMemory(min_free_bytes)) {
				// release the cache of other loaders (up to 'a' of the ImagePlus cache of them if necessary)
				if (massive_mode) {
					// release others regardless of the 'release_others' boolean
					releaseOthers(0.5D);
					// reset
					//massive_mode = true; // NEEDS TESTING, a good debugger would tell me when is this variable changed... Or, set the value always with the method setMassiveMode
					if (enoughFreeMemory(min_free_bytes)) return;
					// awts can be recreated from imps, remove half of them
					if (0 != awts.size()) {
						for (int i=awts.size()/2; i>-1; i--) {
							Image im = awts.removeFirst();
							if (null != im) im.flush();
						}
						if (enoughFreeMemory(min_free_bytes)) return;
					}
					// remove half of the imps
					if (0 != imps.size()) {
						for (int i=imps.size()/2; i>-1; i--) {
							ImagePlus imp = imps.removeFirst();
							if (null != imp) imp.flush();
						}
						System.gc();
						Thread.yield();
						if (enoughFreeMemory(min_free_bytes)) return;
					}
					// finally, release snapshots
					if (0 != snaps.size()) {
						// release almost all snapshots (they're cheap to reload/recreate)
						for (int i=(int)(snaps.size() * 0.25); i>-1; i--) { // 0.9, 0.5 ... still too much, so 0.25
							Image snap = snaps.removeFirst();
							if (null != snap) snap.flush();
						}
						runGC();
						if (enoughFreeMemory(min_free_bytes)) return;
					}
				} else {
					if (release_others) {
						releaseOthers(a);
						if (enoughFreeMemory(min_free_bytes)) return;
					}
					if (0 == imps.size()) {
						// release half the cached awt images
						if (0 != awts.size()) {
							for (int i=awts.size()/3; i>-1; i--) {
								Image im = awts.removeFirst();
								if (null != im) im.flush();
							}
							runGC();
							if (enoughFreeMemory(min_free_bytes)) return;
						}
						if (0 != snaps.size()) {
							// release almost all snapshots (they're cheap to reload/recreate)
							for (int i=(int)(snaps.size() * 0.5); i>-1; i--) {
								Image snap = snaps.removeFirst();
								if (null != snap) snap.flush();
							}
							runGC();
							if (enoughFreeMemory(min_free_bytes)) return;
						}
					}
					// finally:
					if (a > 0.0D && a <= 1.0D) {
						// up to 'a' of the ImagePlus cache:
						for (int i=(int)(imps.size() * a); i>-1; i--) {
							ImagePlus imp = imps.removeFirst();
							if (null != imp) imp.flush();
						}
					} else {
						// just one:
						ImagePlus imp = imps.removeFirst();
						if (null != imp) imp.flush(); // will call System.gc() already
					}
				}

				//Utils.log("imps, awts, snaps size: " + imps.size() + "," + awts.size()  + "," + snaps.size());

				// sanity check:
				if (0 == imps.size() && 0 == awts.size() && 0 == snaps.size()) {
					if (massive_mode) {
						Utils.log("Loader.releaseMemory: last desperate attempt.");
						runGC();
					}
					// in any case:
					return;
				}
			}
		} catch (Exception e) {
			new IJError(e);
			return;
		}
	}

	/** Release memory from other loaders. */
	synchronized private void releaseOthers(double a) {
		if (1 == v_loaders.size()) return;
		if (a <= 0.0D || a > 1.0D) return;
		Iterator it = v_loaders.iterator();
		while (it.hasNext()) {
			Loader loader = (Loader)it.next();
			if (loader.equals(this)) continue;
			else {
				loader.setMassiveMode(false); // otherwise would loop back!
				loader.releaseMemory(a, false, MIN_FREE_BYTES);
			}
		}
		notify();
	}

	private void releaseAll() {
		synchronized (db_lock) {
			lock();
			// second catch, this time locked:
			if (null != IJ.getInstance() && IJ.getInstance().quitting()) {
				unlock();
				return;
			}
			try {
				if (null != imps) {
					for (int i=imps.size()-1; i>-1; i--) {
						ImagePlus imp = imps.remove(i);
						if (null != imp) imp.flush();
					}
					imps = null;
				}
				if (null != awts) {
					for (int i=awts.size()-1; i>-1; i--) {
						Image awt = awts.remove(i);
						if (null != awt) awt.flush();
					}
					awts = null;
				}
				if (null != snaps) {
					for (int i=snaps.size()-1; i>-1; i--) {
						Image snap = snaps.remove(i);
						if (null != snap) snap.flush();
					}
					snaps = null;
				}
				System.gc();
			} catch (Exception e) {
				unlock();
				new IJError(e);
				return;
			}
			unlock();
		}
	}

	/** Just query the cache, does not attempt to reload anything. */
	public Image fetchAWT(long id) {
		Image awt = null;
		synchronized (db_lock) {
			lock();
			awt = awts.get(id);
			unlock();
		}
		return awt;
	}

	/** Removes from the cache and returns it intact, unflushed. Returns null if not found. */
	public Image decacheAWT(long id) {
		Image awt = null;
		synchronized (db_lock) {
			lock();
			awt = awts.remove(id); // where are my lisp macros! Wrapping any function in a synch/lock/unlock could be done crudely with reflection, but what a pain
			unlock();
		}
		return awt;
	}

	public Image fetchImage(Patch p) {
		return fetchImage(p, 1.0);
	}

	/** Fetch a suitable awt.Image for the given mag.
	 * If the mag equals or is below 0.25 (Snapshot.SCALE), the snap is returned.
	 * Else, if the awt is cached and is suitable for that mag or larger, it is returned.
	 * Else, a new awt is made for the given mag.
	 * 
	 * If the magnification is bigger than 1.0, it will return as if the 'mag' was 1.0.
	 * Will return Loader.NOT_FOUND if, err, not found (probably an Exception will print along).
	 * */
	public Image fetchImage(final Patch p, double mag) {
		synchronized (db_lock) {
			lock();
			try {
				if (null == awts) {
					unlock();
					return NOT_FOUND; // when lazy repainting after closing a project, the awts is null
				}
				final long id = p.getId();
				// If the is cached, see if it's suitable
				if (mag - Snapshot.SCALE < 0.001) { // i.e. if mag is 0.25 or lower
					final Image snap = snaps.get(id);
					if (null != snap) {
						unlock();
						return snap;
					}
				}
				// see if the Displayable AWT image is cached and big enough:
				Image awt = awts.get(id);
				if (mag > 1.0) mag = 1.0; // Don't want to create gigantic images!
				if (null != awt) {
					if (mag - (awt.getWidth(null) / (double)p.getWidth()) < 0.001) {
						unlock();
						return awt;
					} else {
						// must remake awt, it's not big enough
						try {
						awts.remove(id);//.flush(); // TODO this flush is hanging the program. Where is the collision? All that changed was the Displayable.getWidth()
						} catch (Exception e) {
							new IJError(e);
						}
						// see if the snap is cached and big enough
						if (Math.abs(mag - Snapshot.SCALE) < 0.001) {
							Image snap = snaps.get(id);
							if (null != snap) {
								unlock();
								return snap; // kind of redundant with the line below
							} else {
								unlock();
								return fetchSnapshot(p); // will create the awt as well
							}
						}
					}
				}

				// if we get here and the awt is not null, it means its size is not appropriate
				// If it's null, it means the snapshot is also null and/or not appropriate

				//Utils.log2("Loader.fetchImage: awt is " + awt + "  cache size: " + awts.size());

				releaseMemory();

				// see if the ImagePlus is cached:
				final ImagePlus imp = imps.get(id);
				if (null != imp) {
					if (null != imp.getProcessor() && null != imp.getProcessor().getPixels()) { // may have been flushed by ImageJ, for example when making images from a stack
						unlock();
						Image image = p.createImage(); //considers c_alphas
						lock();
						if (1.0 != mag) { // make it smaller if possible
							final Image image2 = Snapshot.createSnap(p, image, mag); // reusing the function createSnap to obtain a proper, quality option-respecting awt.
							image.flush();
							image = image2;
						}
						awts.put(id, image);
						Display.repaintSnapshot(p);
						unlock();
						return image;
					} else {
						imp.flush(); // can't hurt
					}
				}
				// else, reload and cache both the imp and the awt

				unlock();
				Image image = p.createImage(); // calls fetchImagePlus, which will lock
				if (null == image) {
					return NOT_FOUND; // fixing synch problems when deleting a Patch
				}
				lock();
				if (1.0 != mag) { // make it smaller if possible
					final Image image2 = Snapshot.createSnap(p, image, mag); // reusing the function createSnap to obtain a proper, quality option-respecting awt.
					image.flush();
					image = image2;
				}
				awts.put(id, image); // this is already done by the call to cacheAWT from p.createImage()
				Display.repaintSnapshot(p);

				unlock();
				return image;

			} catch (Exception e) {
				unlock();
				new IJError(e);
				return NOT_FOUND;
			}
		}
	}

	/** Simply reads from the cache, does no reloading at all. If the ImagePlus is not found in the cache, it returns null and the burden is on the calling method to do reconstruct it if necessary.This is intended for the LayerStack. */
	public ImagePlus fetchImagePlus(long id) {
		synchronized(db_lock) {
			ImagePlus imp = null;
			lock();
			imp = imps.get(id);
			unlock();
			return imp;
		}
	}

	abstract public ImagePlus fetchImagePlus(Patch p);
	abstract public ImagePlus fetchImagePlus(Patch p, boolean create_snap);

	abstract public Object[] fetchLabel(DLabel label);


	/**Returns the ImagePlus as a zipped InputStream of bytes; the InputStream has to be closed by whoever is calling this method. */
	protected InputStream createZippedStream(ImagePlus imp) throws Exception {
		FileInfo fi = imp.getFileInfo();
		Object info = imp.getProperty("Info");
		if (info != null && (info instanceof String)) {
			fi.info = (String)info;
		}
		if (null == fi.description) {
			fi.description = new ij.io.FileSaver(imp).getDescriptionString();
		}
		//see whether this is a stack or not
		/*	//never the case in my program
		if (fi.nImages > 1) {
			IJ.log("saving a stack!");
			//virtual stacks would be supported? I don't think so because the FileSaver.saveAsTiffStack(String path) doesn't.
			if (fi.pixels == null && imp.getStack().isVirtual()) {
				//don't save it!
				IJ.showMessage("Virtual stacks not supported.");
				return false;
			}
			//setup stack things as in FileSaver.saveAsTiffStack(String path)
			fi.sliceLabels = imp.getStack().getSliceLabels();
		}
		*/
		TiffEncoder te = new TiffEncoder(fi);
		ByteArrayInputStream i_stream = null;
		ByteArrayOutputStream o_bytes = new ByteArrayOutputStream();
		DataOutputStream o_stream = null;
		try {
			/* // works, but not significantly faster and breaks older databases (can't read zipped images properly)
			byte[] bytes = null;
			// compress in RAM
			o_stream = new DataOutputStream(new BufferedOutputStream(o_bytes));
			te.write(o_stream);
			o_stream.flush();
			o_stream.close();
			Deflater defl = new Deflater();
			byte[] unzipped_bytes = o_bytes.toByteArray();
			defl.setInput(unzipped_bytes);
			defl.finish();
			bytes = new byte[unzipped_bytes.length]; // this length *should* be enough
			int length = defl.deflate(bytes);
			if (length < unzipped_bytes.length) {
				byte[] bytes2 = new byte[length];
				System.arraycopy(bytes, 0, bytes2, 0, length);
				bytes = bytes2;
			}
			*/
			// old, creates temp file
			o_bytes = new ByteArrayOutputStream(); // clearing
			ZipOutputStream zos = new ZipOutputStream(o_bytes);
			o_stream = new DataOutputStream(new BufferedOutputStream(zos));
			zos.putNextEntry(new ZipEntry(imp.getTitle()));
			te.write(o_stream);
			o_stream.flush(); //this was missing and was 1) truncating the Path images and 2) preventing the snapshots (which are very small) to be saved at all!!
			o_stream.close(); // this should ensure the flush above anyway. This can work because closing a ByteArrayOutputStream has no effect.
			byte[] bytes = o_bytes.toByteArray();

			//Utils.showStatus("Zipping " + bytes.length + " bytes...", false);
			//Utils.debug("Zipped ImagePlus byte array size = " + bytes.length);
			i_stream = new ByteArrayInputStream(bytes);
		} catch (Exception e) {
			Utils.log("Loader: ImagePlus NOT zipped! Problems at writing the ImagePlus using the TiffEncoder.write(dos) :\n " + e);
			//attempt to cleanup:
			try {
				if (null != o_stream) o_stream.close();
				if (null != i_stream) i_stream.close();
			} catch (IOException ioe) {
				Utils.log("Loader: Attempt to clean up streams failed.");
				new IJError(ioe);
			}
			return null;
		}
		return i_stream;
	}


	/** A dialog to open a stack, making sure there is enough memory for it. */
	synchronized public ImagePlus openStack() {
		final OpenDialog od = new OpenDialog("Select stack", OpenDialog.getDefaultDirectory(), null);
		String file_name = od.getFileName();
		if (null == file_name || file_name.toLowerCase().startsWith("null")) return null;
		String dir = od.getDirectory();

		File f = new File(dir + "/" + file_name);
		if (!f.exists()) {
			Utils.showMessage("File " + dir + "/" + file_name  + " does not exist.");
			return null;
		}
		// estimate file size: assumes an uncompressed tif, or a zipped tif with an average compression ratio of 2.5
		long size = f.length() / 1024; // in megabytes
		if (file_name.length() -4 == file_name.toLowerCase().lastIndexOf(".zip")) {
			size = (long)(size * 2.5); // 2.5 is a reasonable compression ratio estimate based on my experience
		}
		int max_iterations = 15;
		while (enoughFreeMemory(size)) {
			if (0 == max_iterations) {
				// leave it to the Opener class to throw an OutOfMemoryExceptionm if so.
				break;
			}
			max_iterations--;
			releaseMemory();
		}
		ImagePlus imp_stack = null;
		try {
			imp_stack = opener.openImage(f.getCanonicalPath());
		} catch (Exception e) {
			new IJError(e);
			return null;
		}
		if (null == imp_stack) {
			Utils.showMessage("Can't open the stack.");
			return null;
		} else if (1 == imp_stack.getStackSize()) {
			Utils.showMessage("Not a stack!");
			return null;
		}
		return imp_stack; // the open... command
	}

	public Bureaucrat importSequenceAsGrid(Layer layer) {
		return importSequenceAsGrid(layer, null);
	}

	public Bureaucrat importSequenceAsGrid(final Layer layer, String dir) {
		return importSequenceAsGrid(layer, dir, null);
	}

	/** Import a sequence of images as a grid, and put them in the layer. If the directory (@param dir) is null, it'll be asked for. The image_file_names can be null, and in any case it's only the names, not the paths. */
	public Bureaucrat importSequenceAsGrid(final Layer layer, String dir, final String[] image_file_names) {
		try {

		String[] all_images = null;
		String file = null; // first file
		File images_dir = null;

		if (null != dir && null != image_file_names) {
			all_images = image_file_names;
			images_dir = new File(dir);
		} else if (null == dir) {
			String[] dn = Utils.selectFile("Select first image");
			if (null == dn) return null;
			dir = dn[0];
			file = dn[1];
			images_dir = new File(dir);
		} else {
			images_dir = new File(dir);
			if (!(images_dir.exists() && images_dir.isDirectory())) {
				Utils.showMessage("Something went wrong:\n\tCan't find directory " + dir);
				return null;
			}
		}
		if (null == image_file_names) all_images = images_dir.list(new ini.trakem2.io.ImageFileFilter("", null));

		if (null == file && all_images.length > 0) {
			file = all_images[0];
		}

		int n_max = all_images.length;

		String preprocessor = "";
		int n_rows = 0;
		int n_cols = 0;
		double bx = 0;
		double by = 0;
		double bt_overlap = 0;
		double lr_overlap = 0;
		boolean link_images = true;
		boolean stitch_tiles = true;
		boolean homogenize_contrast = true;

		/** Need some sort of user properties system. */
		/*
		String username = System.getProperty("user.name");
		if (null != username && (username.equals("albert") || username.equals("cardona"))) {
			preprocessor = ""; //"Adjust_EMMENU_Images";
			link_images = false; //true;
			bt_overlap = 204.8; //102.4;
			lr_overlap = 204.8; //102.4; // testing cross-correlation
			stitch_tiles = true;
			homogenize_contrast = true;
		}
		*/
		// reasonable estimate
		n_rows = n_cols = (int)Math.floor(Math.sqrt(n_max));

		GenericDialog gd = new GenericDialog("Conventions");
		gd.addStringField("file_name_matches: ", "");
		gd.addNumericField("first_image: ", 1, 0);
		gd.addNumericField("last_image: ", n_max, 0);
		gd.addNumericField("number_of_rows: ", n_rows, 0);
		gd.addNumericField("number_of_columns: ", n_cols, 0);
		gd.addMessage("The top left coordinate for the imported grid:");
		gd.addNumericField("base_x: ", 0, 3);
		gd.addNumericField("base_y: ", 0, 3);
		gd.addMessage("Amount of image overlap, in pixels");
		gd.addNumericField("bottom-top overlap: ", bt_overlap, 2); //as asked by Joachim Walter
		gd.addNumericField("left-right overlap: ", lr_overlap, 2);
		gd.addCheckbox("link images", link_images);
		gd.addStringField("preprocess with: ", preprocessor); // the name of a plugin to use for preprocessing the images before importing, which implements PlugInFilter
		gd.addCheckbox("use_cross-correlation", stitch_tiles);
		gd.addSlider("tile_overlap (%): ", 1, 100, 10);
		gd.addSlider("cc_scale (%):", 1, 100, 25);
		gd.addCheckbox("homogenize_contrast", homogenize_contrast);
		StitchingTEM.addStitchingRuleChoice(gd);
		final Component[] c = {
			(Component)gd.getSliders().get(gd.getSliders().size()-2),
			(Component)gd.getNumericFields().get(gd.getNumericFields().size()-2),
			(Component)gd.getSliders().get(gd.getSliders().size()-1),
			(Component)gd.getNumericFields().get(gd.getNumericFields().size()-1)
		};
		// enable the checkbox to control the slider and its associated numeric field:
		Utils.addEnablerListener((Checkbox)gd.getCheckboxes().get(gd.getCheckboxes().size()-1), c, null);

		gd.showDialog();

		if (gd.wasCanceled()) return null;

		final String regex = gd.getNextString();
		Utils.log2(new StringBuffer("using regex: ").append(regex).toString()); // avoid destroying backslashes
		int first = (int)gd.getNextNumber();
		if (first < 1) first = 1;
		int last = (int)gd.getNextNumber();
		if (last < 1) last = 1;
		if (last < first) {
			Utils.showMessage("Last is smaller that first!");
			return null;
		}
		n_rows = (int)gd.getNextNumber();
		n_cols = (int)gd.getNextNumber();
		bx = gd.getNextNumber();
		by = gd.getNextNumber();
		bt_overlap = gd.getNextNumber();
		lr_overlap = gd.getNextNumber();
		link_images = gd.getNextBoolean();
		preprocessor = gd.getNextString().replace(' ', '_'); // just in case
		stitch_tiles = gd.getNextBoolean();
		float cc_percent_overlap = (float)gd.getNextNumber() / 100f;
		float cc_scale = (float)gd.getNextNumber() / 100f;
		homogenize_contrast = gd.getNextBoolean();
		int stitching_rule = gd.getNextChoiceIndex();

		String[] file_names = null;
		if (null == image_file_names) {
			file_names = images_dir.list(new ini.trakem2.io.ImageFileFilter(regex, null));
			Arrays.sort(file_names); //assumes 001, 002, 003 ... that style, since it does binary sorting of strings
		} else {
			file_names = all_images;
		}

		if (0 == file_names.length) {
			Utils.showMessage("No images found.");
			return null;
		}
		// check if the selected image is in the list. Otherwise, shift selected image to the first of the included ones.
		boolean found_first = false;
		for (int i=0; i<file_names.length; i++) {
			if (file.equals(file_names[i])) {
				found_first = true;
				break;
			}
		}
		if (!found_first) {
			file = file_names[0];
			Utils.log("Using " + file + " as the reference image for size.");
		}
		// crop list
		if (last > file_names.length) last = file_names.length -1;
		if (first < 1) first = 1;
		if (1 != first || last != file_names.length) {
			Utils.log("Cropping list.");
			String[] file_names2 = new String[last - first + 1];
			System.arraycopy(file_names, first -1, file_names2, 0, file_names2.length);
			file_names = file_names2;
		}
		// should be multiple of rows and cols
		if (file_names.length != n_rows * n_cols) {
			Utils.log2("n_images:" + file_names.length + "  rows,cols : " + n_rows + "," + n_cols + " total=" + n_rows*n_cols);
			Utils.showMessage("rows * cols does not match with the number of selected images.");
			return null;
		}
		// put in columns
		ArrayList cols = new ArrayList();
		for (int i=0; i<n_cols; i++) {
			String[] col = new String[n_rows];
			for (int j=0; j<n_rows; j++) {
				col[j] = file_names[j*n_cols + i];
			}
			cols.add(col);
		}

		return insertGrid(layer, dir, file, file_names.length, cols, bx, by, bt_overlap, lr_overlap, link_images, preprocessor, stitch_tiles, cc_percent_overlap, cc_scale, homogenize_contrast, stitching_rule);

		} catch (Exception e) {
			new IJError(e);
		}
		return null;
	}

	private ImagePlus preprocess(String preprocessor, ImagePlus imp, String path) {
		if (null == imp) return null;
		try {
			startSetTempCurrentImage(imp);
			IJ.redirectErrorMessages();
			Object ob = IJ.runPlugIn(preprocessor, "[path=" + path + "]");
			ImagePlus pp_imp = WindowManager.getCurrentImage();
			if (null != pp_imp) {
				finishSetTempCurrentImage();
				return pp_imp;
			} else {
				// discard this image
				Utils.log("Ignoring " + imp.getTitle() + " from " + path + " since the preprocessor " + preprocessor + " returned null on it.");
				imp.flush();
				finishSetTempCurrentImage();
				return null;
			}
		} catch (Exception e) {
			new IJError(e);
			finishSetTempCurrentImage();
			Utils.log("Ignoring " + imp.getTitle() + " from " + path + " since the preprocessor " + preprocessor + " throwed an Exception on it.");
			imp.flush();
		}
		return null;
	}

	public Bureaucrat importGrid(Layer layer) {
		return importGrid(layer, null);
	}

	/** Import a grid of images and put them in the layer. If the directory (@param dir) is null, it'll be asked for. */
	public Bureaucrat importGrid(Layer layer, String dir) {
		try {
			String file = null;
			if (null == dir) {
				String[] dn = Utils.selectFile("Select first image");
				if (null == dn) return null;
				dir = dn[0];
				file = dn[1];
			}

		String convention = "cdd"; // char digit digit
		boolean chars_are_columns = true;
		// examine file name
		/*
		if (file.matches("\\A[a-zA-Z]\\d\\d.*")) { // one letter, 2 numbers
			//means:
			//	\A		- beggining of input
			//	[a-zA-Z]	- any letter upper or lower case
			//	\d\d		- two consecutive digits
			//	.*		- any row of chars
			ini_grid_convention = true;
		}
		*/
		// ask for chars->rows, numbers->columns or viceversa
		GenericDialog gd = new GenericDialog("Conventions");
		gd.addStringField("file_name_contains:", "");
		gd.addNumericField("base_x: ", 0, 3);
		gd.addNumericField("base_y: ", 0, 3);
		gd.addMessage("Use: x(any), c(haracter), d(igit)");
		gd.addStringField("convention: ", convention);
		final String[] cr = new String[]{"columns", "rows"};
		gd.addChoice("characters are: ", cr, cr[0]);
		gd.addMessage("[File extension ignored]");

		gd.addNumericField("bottom-top overlap: ", 0, 3); //as asked by Joachim Walter
		gd.addNumericField("left-right overlap: ", 0, 3);
		gd.addCheckbox("link_images", false);
		gd.addStringField("Preprocess with: ", ""); // the name of a plugin to use for preprocessing the images before importing, which implements Preprocess
		gd.addCheckbox("use_cross-correlation", false);
		gd.addSlider("tile_overlap (%): ", 1, 100, 10);
		gd.addSlider("cc_scale (%):", 1, 100, 25);
		gd.addCheckbox("homogenize_contrast", true);
		StitchingTEM.addStitchingRuleChoice(gd);
		final Component[] c = {
			(Component)gd.getSliders().get(gd.getSliders().size()-2),
			(Component)gd.getNumericFields().get(gd.getNumericFields().size()-2),
			(Component)gd.getSliders().get(gd.getSliders().size()-1),
			(Component)gd.getNumericFields().get(gd.getNumericFields().size()-1)
		};
		// enable the checkbox to control the slider and its associated numeric field:
		Utils.addEnablerListener((Checkbox)gd.getCheckboxes().get(gd.getCheckboxes().size()-1), c, null);

		gd.showDialog();
		if (gd.wasCanceled()) {
			return null;
		}
		//collect data
		String regex = gd.getNextString(); // filter away files not containing this tag
		// the base x,y of the whole grid
		double bx = gd.getNextNumber();
		double by = gd.getNextNumber();
		//if (!ini_grid_convention) {
			convention = gd.getNextString().toLowerCase();
		//}
		if (/*!ini_grid_convention && */ (null == convention || convention.equals("") || -1 == convention.indexOf('c') || -1 == convention.indexOf('d'))) { // TODO check that the convention has only 'cdx' chars and also that there is an island of 'c's and of 'd's only.
			Utils.showMessage("Convention '" + convention + "' needs both c(haracters) and d(igits), optionally 'x', and nothing else!");
			return null;
		}
		chars_are_columns = (0 == gd.getNextChoiceIndex());
		double bt_overlap = gd.getNextNumber();
		double lr_overlap = gd.getNextNumber();
		boolean link_images = gd.getNextBoolean();
		String preprocessor = gd.getNextString();
		boolean stitch_tiles = gd.getNextBoolean();
		float cc_percent_overlap = (float)gd.getNextNumber() / 100f;
		float cc_scale = (float)gd.getNextNumber() / 100f;
		boolean homogenize_contrast = gd.getNextBoolean();
		int stitching_rule = gd.getNextChoiceIndex();

		//start magic
		//get ImageJ-openable files that comply with the convention
		File images_dir = new File(dir);
		if (!(images_dir.exists() && images_dir.isDirectory())) {
			Utils.showMessage("Something went wrong:\n\tCan't find directory " + dir);
			return null;
		}
		String[] file_names = images_dir.list(new ImageFileFilter(regex, convention));
		if (null == file && file_names.length > 0) {
			// the 'selected' file
			file = file_names[0];
		}
		Utils.showStatus("Adding " + file_names.length + " patches.");
		if (0 == file_names.length) {
			Utils.log("Zero files match the convention '" + convention + "'");
			return null;
		}

		// How to: select all files, and order their names in a double array as they should be placed in the Display. Then place them, displacing by offset, and resizing if necessary.
		// gather image files:
		Montage montage = new Montage(convention, chars_are_columns);
		montage.addAll(file_names);
		ArrayList cols = montage.getCols(); // an array of Object[] arrays, of unequal length maybe, each containing a column of image file names
		return insertGrid(layer, dir, file, file_names.length, cols, bx, by, bt_overlap, lr_overlap, link_images, preprocessor, stitch_tiles, cc_percent_overlap, cc_scale, homogenize_contrast, stitching_rule);

		} catch (Exception e) {
			new IJError(e);
		}
		return null;
	}

	/**
	 * @param layer The Layer to inser the grid into
	 * @param dir The base dir of the images to open
	 * @param cols The list of columns, containing each an array of String file names in each column.
	 * @param bx The top-left X coordinate of the grid to insert
	 * @param by The top-left Y coordinate of the grid to insert
	 * @param bt_overlap bottom-top overlap of the images
	 * @param lr_overlap left-right overlap of the images
	 * @param link_images Link images to their neighbors.
	 * @param preproprecessor The name of a PluginFilter in ImageJ's plugin directory, to be called on every image prior to insertion.
	 */
	private Bureaucrat insertGrid(final Layer layer, final String dir_, final String first_image_name, final int n_images, final ArrayList cols, final double bx, final double by, final double bt_overlap, final double lr_overlap, final boolean link_images, final String preprocessor, final boolean stitch_tiles, final float cc_percent_overlap, final float cc_scale, final boolean homogenize_contrast, final int stitching_rule) {

		// create a Worker, then give it to the Bureaucrat

		Worker worker = new Worker("Inserting grid") {
			public void run() {
				startedWorking();

		try {
			String dir = dir_;
		ArrayList al = new ArrayList();
		setMassiveMode(true);//massive_mode = true;
		Utils.showProgress(0.0D);
		opener.setSilentMode(true); // less repaints on IJ status bar

		Utils.log2("Preprocessor plugin: " + preprocessor);
		boolean preprocess = null != preprocessor && preprocessor.length() > 0;
		if (preprocess) {
			// check the given plugin
			IJ.redirectErrorMessages();
			startSetTempCurrentImage(null);
			try {
				Object ob = IJ.runPlugIn(preprocessor, "");
				if (!(ob instanceof PlugInFilter)) {
					Utils.showMessageT("Plug in " + preprocessor + " is invalid: does not implement interface PlugInFilter");
					finishSetTempCurrentImage();
					return;
				}
				finishSetTempCurrentImage();
			} catch (Exception e) {
				new IJError(e);
				finishSetTempCurrentImage();
				Utils.showMessageT("Plug in " + preprocessor + " is invalid: ImageJ has trhown an exception when testing it with a null image.");
				return;
			}
		}

		int x = 0;
		int y = 0;
		int largest_y = 0;
		ImagePlus img = null;
		// open the selected image, to use as reference for width and height
		if (!enoughFreeMemory()) releaseMemory();
		dir = dir.replace('\\', '/'); // w1nd0wz safe
		if (!dir.endsWith("/")) dir += "/";
		String path = dir + first_image_name;
		ImagePlus first_img = opener.openImage(path);
		if (null == first_img) {
			Utils.log("Selected image to open first is null.");
			return;
		}

		if (preprocess) first_img = preprocess(preprocessor, first_img, path);
		else preProcess(first_img); // the system wide, if any
		if (null == first_img) return;
		final int first_image_width = first_img.getWidth();
		final int first_image_height = first_img.getHeight();
		final int first_image_type = first_img.getType();
		// start
		final Patch[][] pall = new Patch[cols.size()][((String[])cols.get(0)).length];
		int width, height;
		int k = 0; //counter
		boolean auto_fix_all = false;
		boolean ignore_all = false;
		boolean resize = false;
		if (!ControlWindow.isGUIEnabled()) {
			// headless mode: autofix all
			auto_fix_all = true;
			resize = true;
		}

		startLargeUpdate();
		for (int i=0; i<cols.size(); i++) {
			String[] rows = (String[])cols.get(i);
			if (i > 0) {
				x -= lr_overlap;
			}
			for (int j=0; j<rows.length; j++) {
				if (this.quit) {
					Display.repaint(layer);
					rollback();
					return;
				}
				if (j > 0) {
					y -= bt_overlap;
				}
				// get file name
				String file_name = (String)rows[j];
				path = dir + file_name;
				if (null != first_img && file_name.equals(first_image_name)) {
					img = first_img;
					first_img = null; // release pointer
				} else {
					// open image
					//if (!enoughFreeMemory()) releaseMemory(); // UNSAFE, doesn't wait for GC
					releaseToFit(first_image_width, first_image_height, first_image_type, 1.5f);
					try {
						img = opener.openImage(path);
					} catch (OutOfMemoryError oome) {
						printMemState();
						throw oome;
					}
					// Preprocess ImagePlus
					if (preprocess) {
						img = preprocess(preprocessor, img, path);
						if (null == img) continue;
					} else {
						// use standard project wide , if any
						preProcess(img);
					}
				}
				if (null == img) {
					Utils.log("null image! skipping.");
					pall[i][j] = null;
					continue;
				}

				width = img.getWidth();
				height = img.getHeight();
				int rw = width;
				int rh = height;
				if (width != first_image_width || height != first_image_height) {
					int new_width = first_image_width;
					int new_height = first_image_height;
					if (!auto_fix_all && !ignore_all) {
						GenericDialog gdr = new GenericDialog("Size mismatch!");
						gdr.addMessage("The size of " + file_name + " is " + width + " x " + height);
						gdr.addMessage("but the selected image was " + first_image_width + " x " + first_image_height);
						gdr.addMessage("Adjust to selected image dimensions?");
						gdr.addNumericField("width: ", (double)first_image_width, 0);
						gdr.addNumericField("height: ", (double)first_image_height, 0); // should not be editable ... or at least, explain in some way that the dimensions can be edited just for this image --> done below
						gdr.addMessage("[If dimensions are changed they will apply only to this image]");
						gdr.addMessage("");
						String[] au = new String[]{"fix all", "ignore all"};
						gdr.addChoice("Automate:", au, au[1]);
						gdr.addMessage("Cancel == NO    OK = YES");
						gdr.showDialog();
						if (gdr.wasCanceled()) {
							resize = false;
							// do nothing: don't fix/resize
						}
						resize = true;
						//catch values
						new_width = (int)gdr.getNextNumber();
						new_height = (int)gdr.getNextNumber();
						int iau = gdr.getNextChoiceIndex();
						if (new_width != first_image_width || new_height != first_image_height) {
							auto_fix_all = false;
						} else {
							auto_fix_all = (0 == iau);
						}
						ignore_all = (1 == iau);
						if (ignore_all) resize = false;
					}
					if (resize) {
						//resize Patch dimensions
						rw = first_image_width;
						rh = first_image_height;
					}
				}

				//add new Patch at base bx,by plus the x,y of the grid
				Patch patch = new Patch(layer.getProject(), img.getTitle(), bx + x, by + y, img); // will call back and cache the image
				if (width != rw || height != rh) patch.setDimensions(rw, rh, false);
				layer.add(patch, true);
				//fetchSnapshot(patch); // make sure it is created from the Patch ImagePlus.
				addedPatchFrom(path, patch);
				patch.updateInDatabase("tiff_snapshot"); // otherwise when reopening it has to fetch all ImagePlus and scale and zip them all! This method though creates the awt and the snap, thus filling up memory and slowing down, but it's worth it.
				pall[i][j] = patch;
				//ImageProcessor ip = img.getProcessor();
				//ip.setInterpolate(true);
				//Image snap = ip.resize((int)Math.ceil(img.getWidth() * Snapshot.SCALE), (int)Math.ceil(img.getHeight() * Snapshot.SCALE)).createImage();
				//snaps.put(patch.getId(), snap);

				// debug: does the snap exists?
				/*
				Image sn = snaps.get(patch.getId());
				if (null != sn) {
					Utils.log2("@@@  SNAP YES");
				} else {
					Utils.log2("@@@  SNAP NO");
				}*/

				al.add(patch);
				if (ControlWindow.isGUIEnabled()) {
					layer.getParent().enlargeToFit(patch, LayerSet.NORTHWEST); // northwest to prevent screwing up Patch coordinates.
				}
				y += img.getHeight();
				Utils.showProgress((double)k / n_images);
				k++;
			}
			x += img.getWidth();
			if (largest_y < y) {
				largest_y = y;
			}
			y = 0; //resetting!
		}

		// build list
		final Patch[] pa = new Patch[al.size()];
		int f = 0;
		// list in row-first order
		for (int j=0; j<pall[0].length; j++) { // 'j' is row
			for (int i=0; i<pall.length; i++) { // 'i' is column
				pa[f++] = pall[i][j];
			}
		}
		// optimize repaints: all to background image
		Display.clearSelection(layer);

		// make the first one be top, and the rest under it in left-right and top-bottom order
		for (int j=0; j<pa.length; j++) {
			layer.moveBottom(pa[j]);
		}

		// optimize repaints: all to background image
		Display.clearSelection(layer);

		if (homogenize_contrast) {
			setTaskName("homogenizing contrast");
			// 0 - check that all images are of the same type
			int tmp_type = pa[0].getType();
			for (int e=1; e<pa.length; e++) {
				if (pa[e].getType() != tmp_type) {
					// can't continue
					tmp_type = Integer.MAX_VALUE;
					Utils.log("Can't homogenize histograms: images are not all of the same type.\nFirst offending image is: " + al.get(e));
					break;
				}
			}
			if (Integer.MAX_VALUE != tmp_type) { // checking on error flag
				// Set min and max for all images
				// 1 - fetch statistics for each image
				final ArrayList al_st = new ArrayList();
				final ArrayList al_p = new ArrayList(); // list of Patch ordered by stdDev ASC
				int type = -1;
				for (int i=0; i<pa.length; i++) {
					if (this.quit) {
						Display.repaint(layer);
						rollback();
						return;
					}
					ImagePlus imp = fetchImagePlus(pa[i]); // create the snap
					if (-1 == type) type = imp.getType();
					ImageStatistics i_st = imp.getStatistics();
					// order by stdDev, from small to big
					int q = 0;
					for (Iterator it = al_st.iterator(); it.hasNext(); ) {
						ImageStatistics st = (ImageStatistics)it.next();
						q++;
						if (st.stdDev > i_st.stdDev) break;
					}
					if (q == al.size()) {
						al_st.add(i_st); // append at the end. WARNING if importing thousands of images, this is a potential source of out of memory errors. I could just recompute it when I needed it again below
						al_p.add(pa[i]);
					} else {
						al_st.add(q, i_st);
						al_p.add(q, pa[i]);
					}
				}
				final ArrayList al_p2 = (ArrayList)al_p.clone(); // shallow copy of the ordered list
				// 2 - discard the first and last 25% (TODO: a proper histogram clustering analysis and histogram examination should apply here)
				if (pa.length > 3) { // under 4 images, use them all
					int i=0;
					while (i <= pa.length * 0.25) {
						al_p.remove(i);
						i++;
					}
					int count = i;
					i = pa.length -1 -count;
					while (i > (pa.length* 0.75) - count) {
						al_p.remove(i);
						i--;
					}
				}
				// 3 - compute common histogram for the middle 50% images
				final Patch[] p50 = new Patch[al_p.size()];
				al_p.toArray(p50);
				StackStatistics stats = new StackStatistics(new PatchStack(p50, 1));
				int n = 1;
				switch (type) {
					case ImagePlus.GRAY16:
					case ImagePlus.GRAY32:
						n = 2;
						break;
				}
				// 4 - compute autoAdjust min and max values
				// extracting code from ij.plugin.frame.ContrastAdjuster, method autoAdjust
				int autoThreshold = 0;
				double min = 0;
				double max = 0;
				// once for 8-bit and color, twice for 16 and 32-bit (thus the 2501 autoThreshold value)
				int limit = stats.pixelCount/10;
				int[] histogram = stats.histogram;
				//if (autoThreshold<10) autoThreshold = 5000;
				//else autoThreshold /= 2;
				if (ImagePlus.GRAY16 == type || ImagePlus.GRAY32 == type) autoThreshold = 2500;
				else autoThreshold = 5000;
				int threshold = stats.pixelCount / autoThreshold;
				int i = -1;
				boolean found = false;
				int count;
				do {
					i++;
					count = histogram[i];
					if (count>limit) count = 0;
					found = count > threshold;
				} while (!found && i<255);
				int hmin = i;
				i = 256;
				do {
					i--;
					count = histogram[i];
					if (count > limit) count = 0;
					found = count > threshold;
				} while (!found && i>0);
				int hmax = i;
				if (hmax >= hmin) {
					min = stats.histMin + hmin*stats.binSize;
					max = stats.histMin + hmax*stats.binSize;
					if (min == max) {
						min = stats.min;
						max = stats.max;
					}
				}
				// 5 - compute common mean within min,max range
				double target_mean = getMeanOfRange(stats, min, max);
				Utils.log2("Loader min,max: " + min + ", " + max + ",   target mean: " + target_mean);
				// 6 - apply to all
				for (i=al_p2.size()-1; i>-1; i--) {
					Patch p = (Patch)al_p2.get(i); // the order is different, thus getting it from the proper list
					double dm = target_mean - getMeanOfRange((ImageStatistics)al_st.get(i), min, max);
					p.setMinAndMax(min - dm, max - dm); // displacing in the opposite direction, makes sense, so that the range is drifted upwards and thus the target 256 range for an awt.Image will be closer to the ideal target_mean
					p.putMinAndMax(fetchImagePlus(p, false));
				}

				// 7 - flush away any existing awt images, so that they'll be recreated with the new min and max
				synchronized (db_lock) {
					lock();
					for (i=0; i<pa.length; i++) {
						awts.remove(pa[i].getId());
						snaps.remove(pa[i].getId());
						Utils.log2(i + "removing awt and snap for " + pa[i].getId());
					}
					unlock();
				}
				Display.repaint(layer, new Rectangle(0, 0, (int)layer.getParent().getLayerWidth(), (int)layer.getParent().getLayerHeight()), 0);
			}
		}

		if (stitch_tiles) {
			setTaskName("stitching tiles");
			// create undo
			layer.getParent().createUndoStep(layer);
			// wait until repainting operations have finished (otherwise, calling crop on an ImageProcessor fails with out of bounds exception sometimes)
			if (null != Display.getFront()) Display.getFront().getCanvas().waitForRepaint();
			ControlWindow.startWaitingCursor();
			final StitchingTEM st = new StitchingTEM();
			Thread thread = st.stitch(pa, cols.size(), cc_percent_overlap, cc_scale, bt_overlap, lr_overlap, true, stitching_rule);
			while (StitchingTEM.WORKING == st.getStatus()) {
				if (this.quit) {
					st.quit();
					rollback();
					ControlWindow.endWaitingCursor();
					Display.repaint(layer);
					rollback();
					return;
				}
				try { Thread.sleep(1000); } catch (InterruptedException ie) {}
			}
			if (StitchingTEM.ERROR == st.getStatus()) {
				Utils.log("Cross-correlation FAILED");
				layer.getParent().undoOneStep();
			}
			ControlWindow.endWaitingCursor();
		}

		// link with images on top, bottom, left and right.
		if (link_images) {
			for (int i=0; i<pall.length; i++) { // 'i' is column
				for (int j=0; j<pall[0].length; j++) { // 'j' is row
					Patch p = pall[i][j];
					if (null == p) continue; // can happen if a slot is empty
					if (i>0 && null != pall[i-1][j]) p.link(pall[i-1][j]);
					if (i<pall.length -1 && null != pall[i+1][j]) p.link(pall[i+1][j]);
					if (j>0 && null != pall[i][j-1]) p.link(pall[i][j-1]);
					if (j<pall[0].length -1 && null != pall[i][j+1]) p.link(pall[i][j+1]);
				}
			}
		}

		commitLargeUpdate();

		// resize LayerSet
		int new_width = x;
		int new_height = largest_y;
		layer.getParent().setMinimumDimensions(); //Math.abs(bx) + new_width, Math.abs(by) + new_height);
		// update indexes
		layer.updateInDatabase("stack_index"); // so its done once only
		// create panels in all Displays showing this layer
		Iterator it = al.iterator();
		while (it.hasNext()) {
			Display.add(layer, (Displayable)it.next(), false); // don't set it active, don't want to reload the ImagePlus!
		}
		// update Displays
		Display.update(layer);

		//reset Loader mode
		setMassiveMode(false);//massive_mode = false;

		//debug:
		} catch (Throwable t) {
			new IJError(t);
			rollback();
			setMassiveMode(false); //massive_mode = false;
		}
		finishedWorking();

			}// end of run method
		};

		// watcher thread
		Bureaucrat burro = new Bureaucrat(worker, layer.getProject());
		burro.goHaveBreakfast();
		return burro;
	}

	private double getMeanOfRange(ImageStatistics st, double min, double max) {
		if (min == max) return min;
		double mean = 0;
		int nn = 0;
		int first_bin = 0;
		int last_bin = st.nBins -1;
		for (int b=0; b<st.nBins; b++) {
			if (st.min + st.binSize * b > min) break;
		}
		for (int b=last_bin; b>first_bin; b--) {
			if (st.max - st.binSize * b <= max) break;
		}
		for (int h=first_bin; h<=last_bin; h++) {
			nn += st.histogram[h];
			mean += h * st.histogram[h];
		}
		return mean /= nn;
	}

	/** Used for the revert command. */
	abstract public ImagePlus fetchOriginal(Patch patch);

	/** Set massive mode if not much is cached of the new layer given for loading. */
	public void prepare(Layer layer) {
		ArrayList al = layer.getDisplayables();
		long[] ids = new long[al.size()];
		int next = 0;
		Iterator it = al.iterator();
		while (it.hasNext()) {
			Object ob = it.next();
			if (ob instanceof Patch)
				ids[next++] = ((DBObject)ob).getId();
		}

		int n_cached = 0;
		double area = 0;
		Image snap = null; // snaps are flushed the latest
		for (int i=0; i<next; i++) {
			awts.get(ids[i]); // put at the end if there
			imps.get(ids[i]); // put at the end if there
			if (null != (snap = snaps.get(ids[i]))) { // put the existing (cached) ImagePlus at the end, so they'll be flushed latest
				area += (snap.getWidth(null) / Snapshot.SCALE) * (snap.getHeight(null) / Snapshot.SCALE);
				n_cached++;
			}
		}
		if (0 == next) return; // no need
		else if (n_cached > 0) { // make no assumptions on image compression, assume 8-bit though
			long estimate = (long)(((area / n_cached) * next * 8) / 1024.0D); // 'next' is total
			if (!enoughFreeMemory(estimate)) {
				setMassiveMode(true);//massive_mode = true;
			}
		} else setMassiveMode(false); //massive_mode = true; // nothing loaded, so no clue, set it to load fast by flushing fast.
	}

	/** If the srcRect is null, makes a flat 8-bit or RGB image of the entire layer. Otherwise just of the srcRect. Checks first for enough memory and frees some if feasible. */
	public Bureaucrat makeFlatImage(final Layer[] layer, final Rectangle srcRect_, final double scale, final int c_alphas, final int type, final boolean force_to_file, final boolean quality) {
		final Worker worker = new Worker("making flat images") { public void run() {
			try {
			//
			ImagePlus imp = null;
			String target_dir = null;
			boolean choose_dir = force_to_file;
			// if not saving to a file:
			if (!force_to_file) {
				final long size = (long)Math.ceil((srcRect_.width * scale) * (srcRect_.height * scale) * ( ImagePlus.GRAY8 == type ? 1 : 4 ) * layer.length);
				if (size > IJ.maxMemory() * 0.9) {
					YesNoCancelDialog yn = new YesNoCancelDialog(IJ.getInstance(), "WARNING", "The resulting stack of flat images is too large to fit in memory.\nChoose a directory to save the slices as an image sequence?");
					if (yn.yesPressed()) {
						choose_dir = true;
					} else if (yn.cancelPressed()) {
						finishedWorking();
						return;
					} else {
						choose_dir = false; // your own risk
					}
				}
			}
			if (choose_dir) {
				final DirectoryChooser dc = new DirectoryChooser("Target directory");
				target_dir = dc.getDirectory();
				if (null == target_dir || target_dir.toLowerCase().startsWith("null")) {
					finishedWorking();
					return;
				}
			}
			if (layer.length > 1) {
				// 1 - determine stack voxel depth (by choosing one, if there are layers with different thickness)
				double voxel_depth = 1;
				if (null != target_dir) { // otherwise, saving separately
					ArrayList al_thickness = new ArrayList();
					for (int i=0; i<layer.length; i++) {
						Double t = new Double(layer[i].getThickness());
						if (!al_thickness.contains(t)) al_thickness.add(t);
					}
					if (1 == al_thickness.size()) { // trivial case
						voxel_depth = ((Double)al_thickness.get(0)).doubleValue();
					} else {
						String[] st = new String[al_thickness.size()];
						for (int i=0; i<st.length; i++) {
							st[i] = al_thickness.get(i).toString();
						}
						GenericDialog gdd = new GenericDialog("Choose voxel depth");
						gdd.addChoice("voxel depth: ", st, st[0]);
						gdd.showDialog();
						if (gdd.wasCanceled()) {
							finishedWorking();
							return;
						}
						voxel_depth = ((Double)al_thickness.get(gdd.getNextChoiceIndex())).doubleValue();
					}
				}

				// 2 - get all slices
				ImageStack stack = null;
				for (int i=0; i<layer.length; i++) {
					final ImagePlus slice = getFlatImage(layer[i], srcRect_, scale, c_alphas, type, Displayable.class, quality);
					if (null == slice) {
						Utils.log("Could not retrieve flat image for " + layer[i].toString());
						continue;
					}
					if (null != target_dir) {
						saveToPath(slice, target_dir, layer[i].getPrintableTitle(), ".tif.zip");
					} else {
						if (null == stack) stack = new ImageStack(slice.getWidth(), slice.getHeight());
						stack.addSlice(layer[i].getProject().findLayerThing(layer[i]).toString(), slice.getProcessor());
					}
				}
				if (null != stack) {
					imp = new ImagePlus("z=" + layer[0].getZ() + " to z=" + layer[layer.length-1].getZ(), stack);
					imp.getCalibration().pixelDepth = voxel_depth;
				}
			} else {
				imp = getFlatImage(layer[0], srcRect_, scale, c_alphas, type, Displayable.class, quality);
				if (null != target_dir) {
					saveToPath(imp, target_dir, layer[0].getPrintableTitle(), ".tif.zip");
					imp = null; // to prevent showing it
				}
			}
			if (null != imp) imp.show();
			} catch (Exception e) {
				new IJError(e);
			}
			finishedWorking();
		}};
		Bureaucrat burro = new Bureaucrat(worker, layer[0].getProject());
		burro.goHaveBreakfast();
		return burro;
	}

	/** Will never overwrite, rather, add an underscore and ordinal to the file name. */
	private void saveToPath(final ImagePlus imp, final String dir, final String file_name, final String extension) {
		if (null == imp) {
			Utils.log2("Loader.saveToPath: can't save a null image.");
			return;
		}
		// create a unique file name
		String path = dir + "/" + file_name;
		File file = new File(path + extension);
		int k = 1;
		while (file.exists()) {
			file = new File(path + "_" + k + ".tif.zip");
			k++;
		}
		try {
			new FileSaver(imp).saveAsZip(file.getAbsolutePath());
		} catch (OutOfMemoryError oome) {
			Utils.log2("Not enough memory. Could not save image for " + file_name);
			new IJError(oome);
		} catch (Exception e) {
			Utils.log2("Could not save image for " + file_name);
			new IJError(e);
		}
	}

	public ImagePlus getFlatImage(final Layer layer, final Rectangle srcRect_, final double scale, final int c_alphas, final int type, final Class clazz, final boolean quality) {
		return getFlatImage(layer, srcRect_, scale, c_alphas, type, clazz, null, quality);
	}

	public ImagePlus getFlatImage(final Layer layer, final Rectangle srcRect_, final double scale, final int c_alphas, final int type, final Class clazz, ArrayList al_displ) {
		return getFlatImage(layer, srcRect_, scale, c_alphas, type, clazz, al_displ, false);
	}

	/** Returns a screenshot of the given layer for the given magnification and srcRect. Returns null if the was not enough memory to create it.
	 * @param al_displ The Displayable objects to paint. If null, all those matching Class clazz are included.
	 *
	 * If the 'quality' flag is given, then the flat image is created at a scale of 1.0, and later scaled down using the Image.getScaledInstance method with the SCALE_AREA_AVERAGING flag.
	 *
	 */
	public ImagePlus getFlatImage(final Layer layer, final Rectangle srcRect_, final double scale, final int c_alphas, final int type, final Class clazz, ArrayList al_displ, boolean quality) {
		ImagePlus imp = null;
		try {

			double scaleP = quality ? 1.0 : scale; // TODO: evaluate carefully

			if (null != IJ.getInstance() && ControlWindow.isGUIEnabled()) IJ.getInstance().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
			// dimensions
			int x = 0;
			int y = 0;
			int w = 0;
			int h = 0;
			Rectangle srcRect = (null == srcRect_) ? null : (Rectangle)srcRect_.clone();
			if (null != srcRect) {
				x = srcRect.x;
				y = srcRect.y;
				w = srcRect.width;
				h = srcRect.height;
			} else {
				w = (int)Math.ceil(layer.getLayerWidth());
				h = (int)Math.ceil(layer.getLayerHeight());
				srcRect = new Rectangle(0, 0, w, h);
			}
			Utils.log2("Loader.getFlatImage: using rectangle " + srcRect);
			// estimate image size
			long bytes = (long)((w * h * scaleP * scaleP * (ImagePlus.GRAY8 == type ? 1.0 /*byte*/ : 4.0 /*int*/)));
			Utils.log2("Flat image estimated bytes: " + Long.toString(bytes) + "  w,h : " + (int)Math.ceil(w * scaleP) + "," + (int)Math.ceil(h * scaleP) + (quality ? " (using 'quality' flag: scaling to " + scale + " is done later with proper area averaging)" : ""));

			//synchronized (db_lock) {
			//	lock();
				releaseToFit(bytes); // locks on it's own
			//	unlock();
			//}
			long curr = IJ.maxMemory() - IJ.currentMemory();
			if (curr < bytes) {
				Utils.showMessage("Not enough free RAM for a flat image: current is " + curr);
				return null;
			}
			// go
			synchronized (db_lock) {
				lock();
				releaseMemory(); // savage ...
				unlock();
			}
			BufferedImage bi = null;
			switch (type) {
				case ImagePlus.GRAY8:
					byte[] r = new byte[256];
					byte[] g = new byte[256];
					byte[] b = new byte[256];
					for (int i=0; i<256; i++) {
						r[i]=(byte)i;
						g[i]=(byte)i;
						b[i]=(byte)i;
					}
					bi = new BufferedImage((int)Math.ceil(w * scaleP), (int)Math.ceil(h * scaleP), BufferedImage.TYPE_BYTE_INDEXED, new IndexColorModel(8, 256, r, g, b));
					break;
				case ImagePlus.COLOR_RGB:
					bi = new BufferedImage((int)Math.ceil(w * scaleP), (int)Math.ceil(h * scaleP), BufferedImage.TYPE_INT_ARGB);
					break;
			}
			final Graphics2D g2d = bi.createGraphics();

			// fill background with black, since the getScaledInstance creates an RGB with white background
			if (quality) {
				g2d.setColor(Color.black);
				g2d.fillRect(0, 0, bi.getWidth(), bi.getHeight());
			}


			g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON); // to smooth edges of the images
			g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			synchronized (db_lock) {
				lock();
				releaseMemory(); // savage ...
				unlock();
			}
			ArrayList al_zdispl = null;
			if (null == al_displ) {
				al_displ = layer.getDisplayables(clazz);
				al_zdispl = layer.getParent().getZDisplayables(clazz);
			} else {
				// separate ZDisplayables into their own array
				al_displ = (ArrayList)al_displ.clone();
				//Utils.log2("al_displ size: " + al_displ.size());
				al_zdispl = new ArrayList();
				for (Iterator it = al_displ.iterator(); it.hasNext(); ) {
					Object ob = it.next();
					if (ob instanceof ZDisplayable) {
						it.remove();
						al_zdispl.add(ob);
					}
				}
				// order ZDisplayables by their stack order
				ArrayList al_zdispl2 = layer.getParent().getZDisplayables();
				for (Iterator it = al_zdispl2.iterator(); it.hasNext(); ) {
					Object ob = it.next();
					if (!al_zdispl.contains(ob)) it.remove();
				}
				al_zdispl = al_zdispl2;
			}
	
			// prepare the canvas for the srcRect and magnification
			final AffineTransform at_original = g2d.getTransform();
			final AffineTransform atc = new AffineTransform();
			atc.scale(scaleP, scaleP);
			atc.translate(-srcRect.x, -srcRect.y);
			at_original.preConcatenate(atc);
			g2d.setTransform(at_original);

			//Utils.log2("will paint: " + al_displ.size() + " displ and " + al_zdispl.size() + " zdispl");
			int total = al_displ.size() + al_zdispl.size();
			int count = 0;
			boolean zd_done = false;
			for(Iterator it = al_displ.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				//Utils.log2("d is: " + d);
				// paint the ZDisplayables before the first label, if any
				if (!zd_done && d instanceof DLabel) {
					zd_done = true;
					for (Iterator itz = al_zdispl.iterator(); itz.hasNext(); ) {
						ZDisplayable zd = (ZDisplayable)itz.next();
						if (!zd.isOutOfRepaintingClip(scaleP, srcRect, null)) {
							//zd.paint(g2d, scaleP, srcRect, null, false, c_alphas, layer);
							zd.paint(g2d, scaleP, false, c_alphas, layer);
						}
						count++;
						//Utils.log2("Painted " + count + " of " + total);
					}
				}
				if (!d.isOutOfRepaintingClip(scaleP, srcRect, null)) {
					//d.paint(g2d, scaleP, srcRect, null, false, c_alphas, layer);
					d.paint(g2d, scaleP, false, c_alphas, layer);
				}
				count++;
				//Utils.log2("Painted " + count + " of " + total);
			}
			if (!zd_done) {
				zd_done = true;
				for (Iterator itz = al_zdispl.iterator(); itz.hasNext(); ) {
					ZDisplayable zd = (ZDisplayable)itz.next();
					if (!zd.isOutOfRepaintingClip(scaleP, srcRect, null)) {
						//zd.paint(g2d, scaleP, srcRect, null, false, c_alphas, layer);
						zd.paint(g2d, scaleP, false, c_alphas, layer);
					}
					count++;
					//Utils.log2("Painted " + count + " of " + total);
				}
			}
			// ensure enough memory is available for the processor and a new awt from it
			//synchronized (db_lock) {
			//	lock();
				releaseToFit((long)(bytes*2.3)); // locks on its own
			//	unlock();
			//}
			try {
				if (quality) {
					Image scaled = bi.getScaledInstance((int)(w * scale), (int)(h * scale), Image.SCALE_AREA_AVERAGING); // very slow, but best by far
					bi.flush();
					runGC();
					imp = new ImagePlus(layer.getPrintableTitle(), scaled);
					if (ImagePlus.COLOR_RGB != type) imp.setProcessor(imp.getTitle(), imp.getProcessor().convertToByte(false)); // no need for 'scaling' flag, since the image generated by the getScaledInstance is an RGB

				} else {
					// else the image was made scaled down already
					imp = new ImagePlus(layer.getPrintableTitle(), bi);
				}
			} catch (OutOfMemoryError oome) {
				if (null != imp) imp.flush();
				imp = null;
				Utils.log("Not enough memory to create the ImagePlus. Try scaling it down.");
			}
		} catch (Exception e) {
			if (ControlWindow.isGUIEnabled()) new IJError(e);
			else e.printStackTrace();
			if (null != imp) imp.flush();
			imp = null;
		} finally {
			if (null != IJ.getInstance() && ControlWindow.isGUIEnabled()) IJ.getInstance().setCursor(Cursor.getDefaultCursor());
		}
		return imp;
	}

	/** Generate 256x256 tiles, as many as necessary, to cover the given srcRect, starting at max_scale. Designed to be slow but memory-capable.
	 *
	 * filename = z + "/" + row + "_" + column + "_" + s + ".jpg";
	 * 
	 * row and column run from 0 to n stepsize 1
	 * that is, row = y / ( 256 * 2^s ) and column = x / ( 256 * 2^s )
	 *
	 * z : z-level (slice)
	 * x,y: the row and column
	 * s: scale, which is 1 / (2^s), in integers: 0, 1, 2 ...
	 *
	 *  var MAX_S = Math.floor( Math.log( MAX_Y + 1 ) / Math.LN2 ) - Math.floor( Math.log( Y_TILE_SIZE ) / Math.LN2 ) - 1;
	 *
	 * The module should not be more than 5
	 * At al levels, there should be an even number of rows and columns, except for the coarsest level.
	 * The coarsest level should be at least 5x5 tiles.
	 *
	 * Best results obtained when the srcRect approaches or is a square. Black space will pad the right and bottom edges when the srcRect is not exactly a square.
	 * Only the area within the srcRect is ever included, even if actual data exists beyond.
	 *
	 * Returns the watcher thread, for joining purposes, or null if the dialog is canceled or preconditions ar enot passed.
	 */
	public Bureaucrat makePrescaledTiles(final Layer[] layer, final Class clazz, final Rectangle srcRect, double max_scale_, final int c_alphas, final int type) {
		if (null == layer || 0 == layer.length) return null;
		// choose target directory
		DirectoryChooser dc = new DirectoryChooser("Choose target directory");
		String dir_ = dc.getDirectory();
		if (null == dir_) return null;
		dir_ = dir_.replace('\\', '/'); // Windows fixing
		if (!dir_.endsWith("/")) dir_ += "/"; // Windows users may suffer here

		if (max_scale_ > 1) {
			Utils.log("Prescaled Tiles: using max scale of 1.0");
			max_scale_ = 1; // no point
		}

		final String dir = dir_;
		final double max_scale = max_scale_;
		final int jpeg_quality = ij.plugin.JpegWriter.getQuality();

		Worker worker = new Worker("Creating prescaled tiles") {
			public void cleanup() {
				ij.plugin.JpegWriter.setQuality(jpeg_quality);
			}
			public void run() {
				startedWorking();

		try {

		ij.plugin.JpegWriter.setQuality(85); // 75 %

		// project name
		String pname = layer[0].getProject().getTitle();

		// create 'z' directories if they don't exist: check and ask!

		// start with the highest scale level
		int[] best = determineClosestPowerOfTwo(srcRect.width > srcRect.height ? srcRect.width : srcRect.height);
		int edge_length = best[0];
		final int n_edge_tiles = edge_length / 256;
		Utils.log2("edge_length, n_edge_tiles, best[1] " + best[0] + ", " + n_edge_tiles + ", " + best[1]);
		// thumbnail dimensions
		LayerSet ls = layer[0].getParent();
		double ratio = ls.getLayerWidth() / ls.getLayerHeight();
		double thumb_scale = 1.0;
		if (ratio >= 1) {
			// width is larger or equal than height
			//int tw = 192;
			//int th = (int)(192 / ratio);
			thumb_scale = 192 / ls.getLayerWidth();
		} else {
			//tw = (int)(192 * ratio);
			//th = 192;
			thumb_scale = 192 / ls.getLayerHeight();
		}
		for (int iz=0; iz<layer.length; iz++) {
			if (this.quit) {
				cleanup();
				return;
			}
			// 1 - create a directory 'z' named as the layer's Z coordinate
			String tile_dir = dir + layer[iz].getParent().indexOf(layer[iz]);
			File fdir = new File(tile_dir);
			int tag = 1;
			// Ensure there is a usable directory:
			while (fdir.exists() && !fdir.isDirectory()) {
				fdir = new File(tile_dir + "_" + tag);
			}
			if (!fdir.exists()) {
				fdir.mkdir();
				Utils.log("Created directory " + fdir);
			}
			// if the directory exists already just reuse it, overwritting its files if so.
			final String tmp = fdir.getAbsolutePath();
			if (!tile_dir.equals(tmp)) Utils.log("\tWARNING: directory will not be in the standard location.");
			// debug:
			Utils.log2("tile_dir: " + tile_dir + "\ntmp: " + tmp);
			tile_dir = tmp;
			if (!tile_dir.endsWith("/")) tile_dir += "/"; // Windows users may suffer here
			// 2 - create layer thumbnail, max 192x192
			ImagePlus thumb = getFlatImage(layer[iz], srcRect, thumb_scale, c_alphas, type, clazz, false); // TODO should be true, but it's too much overhead at the moment
			new FileSaver(thumb).saveAsJpeg(tile_dir + "/small.jpg");
			thumb.flush();
			thumb = null;
			// 3 - fill directory with tiles
			if (edge_length < 256) {
				// create single tile per layer
				makeTile(layer[iz], srcRect, max_scale, c_alphas, type, clazz, tile_dir + "0_0_0.jpg");
			} else {
				// create piramid of tiles
				double scale = max_scale;
				int scale_pow = 0;
				int n_et = n_edge_tiles; // cached for local modifications in the loop, works as loop controler
				while (n_et >= best[1]) {
					int tile_side = (int)(256/scale); // 0 < scale <= 1, so no precision lost
					for (int row=0; row<n_et; row++) {
						for (int col=0; col<n_et; col++) {
							Rectangle tile_src = new Rectangle(srcRect.x + tile_side*row, srcRect.y + tile_side*col, tile_side, tile_side); // in absolute coords, magnification later.
							// crop bounds
							if (tile_src.x + tile_src.width > srcRect.x + srcRect.width) tile_src.width = srcRect.x + srcRect.width - tile_src.x;
							if (tile_src.y + tile_src.height > srcRect.x + srcRect.height) tile_src.height = srcRect.y + srcRect.height - tile_src.y;
							// negative tile sizes will be made into black tiles
							makeTile(layer[iz], tile_src, scale, c_alphas, type, clazz, new StringBuffer(tile_dir).append(col).append('_').append(row).append('_').append(scale_pow).append(".jpg").toString()); // should be row_col_scale, but results in transposed tiles in googlebrains, so I inversed it.
						}
					}
					scale_pow++;
					scale = 1 / Math.pow(2, scale_pow); // works as magnification
					n_et /= 2;
				}
			}
		}
		} catch (Exception e) {
			new IJError(e);
		}
		cleanup();
		finishedWorking();

			}// end of run method
		};

		// watcher thread
		Bureaucrat burro = new Bureaucrat(worker, layer[0].getProject());
		burro.goHaveBreakfast();
		return burro;
	}

	/** Will overwrite if the file path exists. */
	private void makeTile(Layer layer, Rectangle srcRect, double mag, int c_alphas, int type, Class clazz, String file_path) throws Exception {
		ImagePlus imp = null;
		if (srcRect.width > 0 && srcRect.height > 0) {
			imp = getFlatImage(layer, srcRect, mag, c_alphas, type, clazz, null, true); // with quality
		} else {
			imp = new ImagePlus("", new ByteProcessor(256, 256)); // black tile
		}
		// correct cropped tiles
		if (imp.getWidth() < 256 || imp.getHeight() < 256) {
			ImagePlus imp2 = new ImagePlus(imp.getTitle(), imp.getProcessor().createProcessor(256, 256));
			// ensure black background for color images
			if (imp2.getType() == ImagePlus.COLOR_RGB) {
				Roi roi = new Roi(0, 0, 256, 256);
				imp2.setRoi(roi);
				imp2.getProcessor().setValue(0); // black
				imp2.getProcessor().fill();
			}
			imp2.getProcessor().insert(imp.getProcessor(), 0, 0);
			imp = imp2;
		}
		// debug
		//Utils.log("would save: " + srcRect + " at " + file_path);
		new FileSaver(imp).saveAsJpeg(file_path);
	}

	/** Find the closest, but larger, power of 2 number for the given edge size */
	private int[] determineClosestPowerOfTwo(int edge) {
		int[] starter = new int[]{1, 2, 3, 5}; // I love primer numbers
		int[] larger = new int[starter.length]; System.arraycopy(starter, 0, larger, 0, starter.length); // I hate java's obscene verbosity
		for (int i=0; i<larger.length; i++) {
			while (larger[i] < edge) {
				larger[i] *= 2;
			}
		}
		int min_larger = larger[0];
		int min_i = 0;
		for (int i=1; i<larger.length; i++) {
			if (larger[i] < min_larger) {
				min_i = i;
				min_larger = larger[i];
			}
		}
		// 'larger' is now larger or equal to 'edge', and will reduce to starter[min_i] tiles squared.
		return new int[]{min_larger, starter[min_i]};
	}

	public void rotatePixels(Patch patch, int direction) {}

	private String last_opened_path = null;

	/** Subclasses can override this method to register the URL to the imported image. */
	public void addedPatchFrom(String path, Patch patch) {}

	public Patch importImage(Project project, double x, double y) {
		return importImage(project, x, y, null);
	}
	/** Import a new image at the given coordinates. If a path is not provided it will be asked for.*/
	public Patch importImage(Project project, double x, double y, String path) {
		if (null == path) {
			OpenDialog od = new OpenDialog("Import image", "");
			String name = od.getFileName();
			if (null == name || 0 == name.length()) return null;
			path = od.getDirectory() + "/" + name;
		}
		releaseMemory(); // some: TODO this should read the header only, and figure out the dimensions to do a releaseToFit(n_bytes) call
		final ImagePlus imp = opener.openImage(path);
		if (null == imp) return null;
		preProcess(imp);
		if (imp.getNSlices() > 1) {
			// a stack!
			Layer layer = Display.getFrontLayer(project);
			if (null == layer) return null;
			importStack(layer, imp, true, path); // TODO: the x,y location is not set
			return null;
		}
		if (0 == imp.getWidth() || 0 == imp.getHeight()) {
			Utils.showMessage("Can't import image of zero width or height.");
			imp.flush();
			return null;
		}
		last_opened_path = path;
		Patch p = new Patch(project, imp.getTitle(), x, y, imp);
		addedPatchFrom(last_opened_path, p);
		if (ControlWindow.isGUIEnabled()) p.getSnapshot().remake(); // must be done AFTER setting the path
		return p;
	}
	public Patch importNextImage(Project project, double x, double y) {
		if (null == last_opened_path) {
			return importImage(project, x, y);
		}
		int i_slash = last_opened_path.lastIndexOf("/");
		String dir_name = last_opened_path.substring(0, i_slash);
		File dir = new File(dir_name);
		String last_file = last_opened_path.substring(i_slash + 1);
		String[] file_names = dir.list();
		String next_file = null;
		final String exts = "tiftiffjpgjpegpnggifzipdicombmppgm";
		for (int i=0; i<file_names.length; i++) {
			if (last_file.equals(file_names[i]) && i < file_names.length -1) {
				// loop until finding a suitable next
				for (int j=i+1; j<file_names.length; j++) {
					String ext = file_names[j].substring(file_names[j].lastIndexOf('.') + 1).toLowerCase();
					if (-1 != exts.indexOf(ext)) {
						next_file = file_names[j];
						break;
					}
				}
				break;
			}
		}
		if (null == next_file) {
			Utils.showMessage("No more files after " + last_file);
			return null;
		}
		releaseMemory(); // some: TODO this should read the header only, and figure out the dimensions to do a releaseToFit(n_bytes) call
		ImagePlus imp = opener.openImage(dir_name, next_file);
		preProcess(imp);
		if (null == imp) return null;
		if (0 == imp.getWidth() || 0 == imp.getHeight()) {
			Utils.showMessage("Can't import image of zero width or height.");
			imp.flush();
			return null;
		}
		last_opened_path = dir + "/" + next_file;
		Patch p = new Patch(project, imp.getTitle(), x, y, imp);
		addedPatchFrom(last_opened_path, p);
		p.getSnapshot().remake(); // must be done AFTER setting the path
		return p;
	}

	public void importStack(Layer first_layer, ImagePlus imp_stack_, boolean ask_for_data) {
		importStack(first_layer, imp_stack_, ask_for_data, null);
	}
	/** Imports an image stack from a multitiff file and places each slice in the proper layer, creating new layers as it goes. If the given stack is null, popup a file dialog to choose one*/
	public void importStack(Layer first_layer, ImagePlus imp_stack_, boolean ask_for_data, String filepath) {
		Utils.log2("Loader.importStack filepath: " + filepath);
		if (null == first_layer) return;
		/* On drag and drop the stack is not null! */ //Utils.log2("imp_stack_ is " + imp_stack_);
		try {
			ImagePlus[] imp_stacks = null;
			if (null == imp_stack_) {
				imp_stacks = Utils.findOpenStacks();
			} else {
				imp_stacks = new ImagePlus[]{imp_stack_};
			}
			ImagePlus imp_stack = null;
			// ask to open a stack if it's null
			if (null == imp_stacks) {
				imp_stack = first_layer.getProject().getLoader().openStack();
			} else {
				// if there's only one, use that one
				if (1 == imp_stacks.length) {
					//YesNoCancelDialog yn = new YesNoCancelDialog(IJ.getInstance(), "Select?", "Import the stack " + imp_stacks[0].getTitle() + " ?");
					//if (!yn.yesPressed()) return; // TODO a layer may be left created..
					imp_stack = imp_stacks[0];
				} else {
					// choose one from the list
					GenericDialog gd = new GenericDialog("Choose one");
					gd.addMessage("Choose a stack from the list or 'open...' to bring up a file chooser dialog:");
					String[] list = new String[imp_stacks.length +1];
					for (int i=0; i<list.length -1; i++) {
						list[i] = imp_stacks[i].getTitle();
					}
					list[list.length-1] = "[  Open stack...  ]";
					gd.addChoice("choose stack: ", list, list[0]);
					gd.showDialog();
					if (gd.wasCanceled()) {
						return;
					}
					int i_choice = gd.getNextChoiceIndex();
					if (list.length-1 == i_choice) { // the open... command
						imp_stack = first_layer.getProject().getLoader().openStack();
					} else {
						imp_stack = imp_stacks[i_choice];
					}
				}
			}
			// check:
			if (null == imp_stack) {
				return;
			}

			final String props = (String)imp_stack.getProperty("Info");

			// check if it's amira labels stack to prevent missimports
			if (null != props && -1 != props.indexOf("Materials {")) {
				YesNoDialog yn = new YesNoDialog(IJ.getInstance(), "Warning", "You are importing a stack of Amira labels as a regular image stack. Continue?");
				if (!yn.yesPressed()) return;
			}

			String dir = imp_stack.getFileInfo().directory;
			double layer_width = first_layer.getLayerWidth();
			double layer_height= first_layer.getLayerHeight();
			double current_thickness = first_layer.getThickness();
			double thickness = current_thickness;
			boolean expand_layer_set = false;
			boolean lock_stack = false;
			int anchor = LayerSet.NORTHWEST; //default
			if (ask_for_data) {
				// ask for slice separation in pixels
				GenericDialog gd = new GenericDialog("Slice separation?");
				gd.addMessage("Please enter the slice thickness, in pixels");
				gd.addNumericField("slice thickness: ", imp_stack.getCalibration().pixelDepth / imp_stack.getCalibration().pixelHeight, 3); // assuming pixelWidth == pixelHeight
				if (layer_width != imp_stack.getWidth() || layer_height != imp_stack.getHeight()) {
					gd.addCheckbox("Resize canvas to fit stack", true);
					gd.addChoice("Anchor: ", LayerSet.ANCHORS, LayerSet.ANCHORS[0]);
				}
				gd.addCheckbox("Lock stack", false);
				gd.showDialog();
				if (gd.wasCanceled()) {
					if (null == imp_stacks) { // flush only if it was not open before
						imp_stack.flush();
					}
					return;
				}
				if (layer_width != imp_stack.getWidth() || layer_height != imp_stack.getHeight()) {
					expand_layer_set = gd.getNextBoolean();
					anchor = gd.getNextChoiceIndex();
				}
				lock_stack = gd.getNextBoolean();
				thickness = gd.getNextNumber();
				// check provided thickness with that of the first layer:
				if (thickness != current_thickness) {
					if (!(1 == first_layer.getParent().size() && first_layer.isEmpty())) {
						YesNoCancelDialog yn = new YesNoCancelDialog(IJ.getInstance(), "Mismatch!", "The current layer's thickness is " + current_thickness + "\nwhich is " + (thickness < current_thickness ? "larger":"smaller") + " than\nthe desired " + thickness + " for each stack slice.\nAdjust current layer's thickness to " + thickness + " ?");
						if (!yn.yesPressed()) {
							if (null != imp_stack_) imp_stack.flush(); // was opened new
							return;
						}
					} // else adjust silently
					first_layer.setThickness(thickness);
				}
			}

			if (null == imp_stack.getStack()) {
				Utils.showMessage("Not a stack.");
				return;
			}

			// WARNING: there are fundamental issues with calibration, because the Layer thickness is disconnected from the Calibration pixelDepth

			// set LayerSet calibration if there is no calibration
			boolean calibrate = true;
			if (ask_for_data && first_layer.getParent().isCalibrated()) {
				YesNoDialog yn = new YesNoDialog("Calibration", "The layer set is already calibrated. Override with the stack calibration values?");
				if (!yn.yesPressed()) {
					calibrate = false;
				}
			}
			if (calibrate) {
				first_layer.getParent().setCalibration(imp_stack.getCalibration());
			}

			if (layer_width < imp_stack.getWidth() || layer_height < imp_stack.getHeight()) {
				expand_layer_set = true;
			}

			if (null == filepath) {
				// try to get it from the original FileInfo
				FileInfo fi = imp_stack.getOriginalFileInfo();
				if (null != fi && null != fi.directory && null != fi.fileName) {
					filepath = fi.directory + fi.fileName; // the fi.directory already ends in '/'  - potential MSWindows failing point
				}
				Utils.log2("Getting filepath from FileInfo: " + filepath);
			}

			// Place the first slice in the current layer, and then query the parent LayerSet for subsequent layers, and create them if not present.
			Patch last_patch = this.importStackAsPatches(first_layer.getProject(), first_layer, imp_stack, null != imp_stack_ && null != imp_stack_.getCanvas(), filepath);
			if (null != last_patch) last_patch.setLocked(lock_stack);

			if (expand_layer_set) {
				last_patch.getLayer().getParent().setMinimumDimensions();
			}

			Utils.log2("props: " + props);

			// check if it's an amira stack, then ask to import labels
			if (null != props && -1 == props.indexOf("Materials {") && -1 != props.indexOf("CoordType")) {
				YesNoDialog yn = new YesNoDialog(IJ.getInstance(), "Amira Importer", "Import labels as well?");
				if (yn.yesPressed()) {
					// select labels
					ArrayList al = AmiraImporter.importAmiraLabels(first_layer, last_patch.getX(), last_patch.getY(), imp_stack.getOriginalFileInfo().directory);
					if (null != al) {
						// import all created AreaList as nodes in the ProjectTree under a new imported_segmentations node
						first_layer.getProject().getProjectTree().insertSegmentations(first_layer.getProject(), al);
						// link them to the images
						// TODO
					}
				}
			}

			// it is safe not to flush the imp_stack, because all its resources are being used anyway (all the ImageProcessor), and it has no awt.Image. Unless it's being shown in ImageJ, and then it will be flushed on its own when the user closes its window.
		} catch (Exception e) {
			new IJError(e);
			return;
		}
	}

	abstract protected Patch importStackAsPatches(final Project project, final Layer first_layer, final ImagePlus stack, final boolean as_copy, String filepath);

	protected String export(Project project, File fxml) {
		return export(project, fxml, true);
	}

	/** Exports the project and its images (optional); if export_images is true, it will be asked for confirmation anyway -beware: for FSLoader, images are not exported since it doesn't own them; only their path.*/
	protected String export(final Project project, final File fxml, boolean export_images) {
		String path = null;
		if (null == project || null == fxml) return null;
		try {
			if (export_images && !(this instanceof FSLoader))  {
				final YesNoCancelDialog yn = ini.trakem2.ControlWindow.makeYesNoCancelDialog("Export images?", "Export images as well?");
				if (yn.cancelPressed()) return null;
				if (yn.yesPressed()) export_images = true;
				else export_images = false; // 'no' option
			}
			// 1 - get headers in DTD format
			final StringBuffer sb_header = new StringBuffer("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<!DOCTYPE ").append(project.getDocType()).append(" [\n");

			final HashSet hs = new HashSet();
			project.exportDTD(sb_header, hs, "\t");

			sb_header.append("] >\n\n");

			//  2 - fill in the data
			String patches_dir = null;
			if (export_images) {
				patches_dir = makePatchesDir(fxml);
			}
			final StringBuffer sb_body = new StringBuffer();
			project.exportXML(sb_body, "", patches_dir);

			// 3 - save XML file
			if (Utils.saveToFile(fxml, sb_header.toString() + sb_body.toString())) {
				this.changes = false;
				path = fxml.getAbsolutePath();
			} else {
				Utils.log2("Failed to save XML file.");
				return null;
			}

			// Remove the patches_dir if empty (can happen when doing a "save" on a FSLoader project if no new Patch have been created that have no path.
			if (export_images) {
				File fpd = new File(patches_dir);
				if (fpd.exists() && fpd.isDirectory()) {
					// check if it contains any files
					File[] ff = fpd.listFiles();
					boolean rm = true;
					for (int k=0; k<ff.length; k++) {
						if (!ff[k].isHidden()) {
							// one non-hidden file found.
							rm = false;
							break;
						}
					}
					if (rm) {
						try {
							fpd.delete();
						} catch (Exception e) {
							Utils.log2("Could not delete empty directory " + patches_dir);
							new IJError(e);
						}
					}
				}
			}

		} catch (Exception e) {
			new IJError(e);
		}
		return path;
	}

	/** Calls saveAs() unless overriden. Returns full path to the xml file. */
	public String save(Project project) { // yes the project is the same project pointer, which for some reason I never committed myself to place it in the Loader class as a field.
		String path = saveAs(project);
		if (null != path) this.changes = false;
		return path;
	}

	/** Exports to an XML file chosen by the user. Images exist already in the file system, so none are exported. Returns the full path to the xml file. */
	public String saveAs(Project project) {
		// Select a file to export to
		File fxml = Utils.chooseFile(null, ".xml");
		if (null == fxml) return null;
		String path = export(project, fxml);
		if (null != path) this.changes = false;
		return path;
	}

	/** Parses the xml_path and returns the folder in the same directory that has the same name plus "_images". */
	public String extractRelativeFolderPath(final File fxml) {
		try {
			String patches_dir = fxml.getParent() + "/" + fxml.getName();
			if (patches_dir.toLowerCase().lastIndexOf(".xml") == patches_dir.length() - 4) {
				patches_dir = patches_dir.substring(0, patches_dir.lastIndexOf('.'));
			}
			return patches_dir + "_images";
		} catch (Exception e) {
			new IJError(e);
			return null;
		}
	}

	protected String makePatchesDir(final File fxml) {
		// Create a directory to store the images
		String patches_dir = extractRelativeFolderPath(fxml);
		if (null == patches_dir) return null;
		File dir = new File(patches_dir);
		String patches_dir2 = null;
		int i = 1;
		while (dir.exists()) {
			patches_dir2 = patches_dir + "_" + Integer.toString(i);
			dir = new File(patches_dir2);
			i++;
		}
		if (null != patches_dir2) patches_dir = patches_dir2;
		if (null == patches_dir) return null;
		try {
			dir.mkdir();
		} catch (Exception e) {
			new IJError(e);
			Utils.showMessage("Could not create a directory for the images.");
			return null;
		}
		if (File.separatorChar != patches_dir.charAt(patches_dir.length() -1)) {
			patches_dir += "/";
		}
		return patches_dir;
	}

	/** Returns the path to the saved image. */
	public String exportImage(Patch patch, String path, boolean overwrite) {
		// save only if not there already
		if (!path.endsWith(".zip")) path += ".zip";
		if (null == path || (!overwrite && new File(path).exists())) return null;
		synchronized(db_lock) {
			try {
				ImagePlus imp = fetchImagePlus(patch, false); // locks on its own
				lock();
				if (null == imp) {
					// something went wrong
					Utils.log("Loader.exportImage: Could not fetch a valid ImagePlus for " + patch.getId());
					unlock();
					return null;
				} else {
					new FileSaver(imp).saveAsZip(path);
				}
			} catch (Exception e) {
				Utils.log("Could not save an image for Patch #" + patch.getId() + " at: " + path);
				new IJError(e);
				unlock();
				return null;
			}
			unlock();
		}
		return path;
	}

	/** Attempts, but does not guarantee, that the snapshots falling within the given clipRect and srcRect will be loaded, in chuncks of n_bytes at a time. */
	public void preloadSnapshots(final Layer layer, final double magnification, final Rectangle srcRect, final Rectangle clipRect, final int n_bytes) {}

	/** Whether any changes need to be saved. */
	public boolean hasChanges() {
		return this.changes;
	}

	public void setChanged(final boolean changed) {
		this.changes = changed;
		//Utils.printCaller(this, 7);
	}

	/** Returns null unless overriden. This is intended for FSLoader projects. */
	public String getPath(final Patch patch) { return null; }

	/** Does nothing unless overriden. */
	public void setupMenuItems(final JMenu menu, final Project project) {}

	/** Test whether this Loader needs recurrent calls to a "save" of some sort, such as for the FSLoader. */
	public boolean isAsynchronous() {
		// in the future, DBLoader may also be asynchronous
		return this.getClass().equals(FSLoader.class);
	}

	/** Throw away all awts and snaps that depend on this image, so that they will be recreated next time they are needed. */
	public void deCache(final ImagePlus imp) {
		synchronized(db_lock) {
			lock();
			long[] a = imps.getAll(imp);
			Utils.log2("deCaching " + a.length);
			if (null == a) return;
			deCache(awts, a);
			deCache(snaps, a);
			unlock();
		}
	}
	/** WARNING: not synchronized */
	private void deCache(final FIFOImageMap f, final long[] ids) {
		for (int i=0; i<ids.length; i++) {
			f.remove(ids[i]);
		}
	}


	static private Object temp_current_image_lock = new Object();
	static private boolean temp_in_use = false;
	static private ImagePlus previous_current_image = null;

	static public void startSetTempCurrentImage(final ImagePlus imp) {
		synchronized (temp_current_image_lock) {
			//Utils.log2("temp in use: " + temp_in_use);
			while (temp_in_use) { try { temp_current_image_lock.wait(); } catch (InterruptedException ie) {} }
			temp_in_use = true;
			previous_current_image = WindowManager.getCurrentImage();
			WindowManager.setTempCurrentImage(imp);
		}
	}

	/** This method MUST always be called after startSetTempCurrentImage(ImagePlus imp) has been called and the action on the image has finished. */
	static public void finishSetTempCurrentImage() {
		synchronized (temp_current_image_lock) {
			WindowManager.setTempCurrentImage(previous_current_image); // be nice
			temp_in_use = false;
			temp_current_image_lock.notifyAll();
		}
	}

	static public void setTempCurrentImage(final ImagePlus imp) {
		synchronized (temp_current_image_lock) {
			while (temp_in_use) { try { temp_current_image_lock.wait(); } catch (InterruptedException ie) {} }
			temp_in_use = true;
			WindowManager.setTempCurrentImage(imp);
			temp_in_use = false;
			temp_current_image_lock.notifyAll();
		}
	}

	private String preprocessor = null;

	public void setPreprocessor(String plugin_class_name) {
		if (null == plugin_class_name || 0 == plugin_class_name.length()) {
			this.preprocessor = null;
			return;
		}
		// just for the sake of it:
		plugin_class_name = plugin_class_name.replace(' ', '_');
		// check that it can be instantiated
		try {
			startSetTempCurrentImage(null);
			IJ.redirectErrorMessages();
			Object ob = IJ.runPlugIn(plugin_class_name, "");
			if (null == ob) {
				Utils.showMessageT("The preprocessor plugin " + plugin_class_name + " was not found.");
			} else if (!(ob instanceof PlugInFilter)) {
				Utils.showMessageT("Plug in '" + plugin_class_name + "' is invalid: does not implement PlugInFilter");
			} else { // all is good:
				this.preprocessor = plugin_class_name;
			}
		} catch (Exception e) {
			e.printStackTrace();
			Utils.showMessageT("Plug in " + plugin_class_name + " is invalid: ImageJ has thrown an exception when testing it with a null image.");
		} finally {
			finishSetTempCurrentImage();
		}
	}

	public String getPreprocessor() {
		return preprocessor;
	}

	/** Preprocess an image before TrakEM2 ever has a look at it with a system-wide defined preprocessor plugin, specified in the XML file and/or from within the Display properties dialog. Does not lock, and should always run within locking/unlocking statements. */
	protected final void preProcess(final ImagePlus imp) {
		if (null == preprocessor) {
			/*
			String username = System.getProperty("user.name");
			if (username.equals("albert") || username.equals("cardona")) {
				setPreprocessor("Preprocessor_Smooth");
				if (null == preprocessor) {
					Utils.log2("WARNING: Preprocessor_Smooth is not present.");
					return;
				}
			} else {
				return;
			}*/
			return;
		}
		// access to WindowManager.setTempCurrentImage(...) is locked within the Loader
		startSetTempCurrentImage(imp);
		try {
			IJ.redirectErrorMessages();
			IJ.runPlugIn(preprocessor, "");
		} catch (Exception e) {
			new IJError(e);
		} finally {
			finishSetTempCurrentImage();
		}
		// reset flag
		imp.changes = false;
	}

	/** Specific options for the Loader which existed as attributes to the Project XML node. */
	public void parseXMLOptions(final Hashtable ht_attributes) {
		Object ob = ht_attributes.get("preprocessor");
		if (ob != null) setPreprocessor((String)ob);
	}

	///////////////////////


	/** List of jobs running on this Loader. */
	private ArrayList al_jobs = new ArrayList();
	private JPopupMenu popup_jobs = null;
	private final Object popup_lock = new Object();
	private boolean popup_locked = false;

	/** Adds a new job to monitor.*/
	public void addJob(Bureaucrat burro) {
		synchronized (popup_lock) {
			while (popup_locked) try { popup_lock.wait(); } catch (InterruptedException ie) {}
			popup_locked = true;
			al_jobs.add(burro);
			popup_locked = false;
			popup_lock.notifyAll();
		}
	}
	public void removeJob(Bureaucrat burro) {
		synchronized (popup_lock) {
			while (popup_locked) try { popup_lock.wait(); } catch (InterruptedException ie) {}
			popup_locked = true;
			if (null != popup_jobs && popup_jobs.isVisible()) {
				popup_jobs.setVisible(false);
			}
			al_jobs.remove(burro);
			popup_locked = false;
			popup_lock.notifyAll();
		}
	}
	public JPopupMenu getJobsPopup(Display display) {
		synchronized (popup_lock) {
			while (popup_locked) try { popup_lock.wait(); } catch (InterruptedException ie) {}
			popup_locked = true;
			this.popup_jobs = new JPopupMenu("Cancel jobs:");
			int i = 1;
			for (Iterator it = al_jobs.iterator(); it.hasNext(); ) {
				Bureaucrat burro = (Bureaucrat)it.next();
				JMenuItem item = new JMenuItem("Job " + i + ": " + burro.getTaskName());
				item.addActionListener(display);
				popup_jobs.add(item);
				i++;
			}
			popup_locked = false;
			popup_lock.notifyAll();
		}
		return popup_jobs;
	}
	/** Names as generated for popup menu items in the getJobsPopup method. If the name is null, it will cancel the last one. */
	public void quitJob(String name) {
		Object ob = null;
		synchronized (popup_lock) {
			while (popup_locked) try { popup_lock.wait(); } catch (InterruptedException ie) {}
			popup_locked = true;
			if (null == name && al_jobs.size() > 0) {
				ob = al_jobs.get(al_jobs.size()-1);
			}  else {
				int i = Integer.parseInt(name.substring(4, name.indexOf(':')));
				if (i >= 1 && i <= al_jobs.size()) ob = al_jobs.get(i-1); // starts at 1
			}
			popup_locked = false;
			popup_lock.notifyAll();
		}
		if (null != ob) {
			// will wait until worker returns
			((Bureaucrat)ob).quit(); // will require the lock
		}
		synchronized (popup_lock) {
			while (popup_locked) try { popup_lock.wait(); } catch (InterruptedException ie) {}
			popup_locked = true;
			popup_jobs = null;
			popup_locked = false;
			popup_lock.notifyAll();
		}
		Utils.showStatus("Job canceled.");
	}

	protected final void printMemState() {
		Utils.log2(new StringBuffer("mem in use: ").append((IJ.currentMemory() * 100.0f) / max_memory).append('%')
		                    .append("\n\timps: ").append(imps.size())
				    .append("\n\tawts: ").append(awts.size())
				    .append("\n\tsnaps: ").append(snaps.size())
			   .toString());
	}

	/** Fixes paths before presenting them to the file system, in an OS-dependent manner. */
	protected final ImagePlus openImage(String path) {
		// supporting samba networks
		if (IJ.isWindows() && path.startsWith("//")) {
			path = path.replace('/', '\\');
		}
		// debug:
		Utils.log2("opening image " + path);
		//Utils.printCaller(this, 10);
		return opener.openImage(path);
	}

	/** Check if the snap or the awt exists to paint as a snap. */
	public boolean isSnapPaintable(final long id) {
		synchronized (db_lock) {
			lock();
			if (snaps.contains(id) || awts.contains(id) /*|| imps.contains(id)*/) { // the other two tests may be necessary, but it's acceptable without them.
				unlock();
				return true;
			}
			unlock();
		}
		return false;
	}
}
