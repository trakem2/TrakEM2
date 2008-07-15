/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005,2006,2007,2008 Albert Cardona and Rodney Douglas.

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

*/

package ini.trakem2.utils;

import ini.trakem2.ControlWindow;
import ini.trakem2.display.YesNoDialog;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.persistence.Loader;
import ini.trakem2.imaging.FloatProcessorT2;


import ij.IJ;
import ij.ImagePlus;
import ij.Menus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.YesNoCancelDialog;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.text.TextWindow;
import ij.measure.ResultsTable;
import ij.process.*;
import ij.io.*;
import ij.process.ImageProcessor;
import ij.process.ImageConverter;

import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Color;
import java.awt.Component;
import java.awt.MenuBar;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Area;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.*;
import java.awt.event.ItemListener;
import java.awt.event.ItemEvent;
import java.awt.event.MouseEvent;
import java.awt.Event;
import javax.swing.SwingUtilities;

import java.lang.reflect.Field;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Vector;
import java.util.Calendar;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/** Utils class: stores generic widely used methods. In particular, those for logging text messages (for debugging) and also some math and memory utilities.
 *
 *
 */
public class Utils implements ij.plugin.PlugIn {

	static public String version = "0.5w 2008-07-09";

	static public boolean debug = false;
	static public boolean debug_mouse = false;
	static public boolean debug_sql = false;
	static public boolean debug_event = false;
	static public boolean debug_clip = false; //clip for repainting
	static public boolean debug_thing = false;

	/** The error to use in floating-point or double floating point literal comparisons. */
	static public final double FL_ERROR = 0.0000001;

	static public void debug(String msg) {
		if (debug) IJ.log(msg);
	}

	static public void debugMouse(String msg) {
		if (debug_mouse) IJ.log(msg);
	}

	/** Avoid waiting on the AWT thread repainting ImageJ's log window. */
	static private class LogDispatcher extends Thread {
		private final StringBuffer cache = new StringBuffer();
		private boolean loading = false;
		private boolean go = true;
		public LogDispatcher() {
			super("T2-Log-Dispatcher");
			setPriority(Thread.NORM_PRIORITY);
			try { setDaemon(true); } catch (Exception e) { e.printStackTrace(); }
			start();
		}
		public final void quit() {
			go = false;
			synchronized (this) { notify(); }
		}
		public final void log(final String msg) {
			try {
				synchronized (cache) {
					loading = true; // no need to synch, variable setting is atomic
					if (0 != cache.length()) cache.append('\n');
					cache.append(msg);
					loading = false;
				}
				Thread.yield();
				if (loading) return;
			} catch (Exception e) {
				e.printStackTrace();
			}
			try {
				synchronized (this) { notify(); }
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		public void run() {
			while (go) {
				try {
					synchronized (this) { wait(); }
					synchronized (cache) {
						if (0 != cache.length()) IJ.log(cache.toString());
						cache.setLength(0);
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}
	static private LogDispatcher logger = new LogDispatcher();

	/** Avoid waiting on the AWT thread repainting ImageJ's status bar.
	    Waits 100 ms before printing the status message; if too many status messages are being sent, the last one overrides all. */
	static private final class StatusDispatcher extends Thread {
		private String msg = null;
		private boolean loading = false;
		private boolean go = true;
		private double progress = -1;
		public StatusDispatcher() {
			super("T2-Status-Dispatcher");
			setPriority(Thread.NORM_PRIORITY);
			try { setDaemon(true); } catch (Exception e) { e.printStackTrace(); }
			start();
		}
		public final void quit() {
			go = false;
			synchronized (this) { notify(); }
		}
		public final void showStatus(final String msg) {
			try {
				synchronized (this) {
					this.msg = msg;
					notify();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		public final void showProgress(final double progress) {
			try {
				synchronized (this) {
					this.progress = progress;
					notify();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		public void run() {
			while (go) {
				try {
					// first part ensures it gets printed even if the notify was issued while not waiting
					synchronized (this) {
						if (null != msg) {
							IJ.showStatus(msg);
							msg = null;
						}
						if (-1 != progress) {
							IJ.showProgress(progress);
							progress = -1;
						}
						wait();
					}
					// allow some time for overwriting of messages
					Thread.sleep(100);
					/* // should not be needed
					// print the msg if necessary
					synchronized (this) {
						if (null != msg) {
							IJ.showStatus(msg);
							msg = null;
						}
					}
					*/
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}
	static private StatusDispatcher status = new StatusDispatcher();

	/** Initialize house keeping threads. */
	static public void setup(final ControlWindow master) { // the ControlWindow acts as a switch: nobody cna controls this because the CW constructor is private
		if (null == status) status = new StatusDispatcher();
		if (null == logger) logger = new LogDispatcher();
	}

	/** Destroy house keeping threads. */
	static public void destroy(final ControlWindow master) {
		if (null != status) { status.quit(); status = null; }
		if (null != logger) { logger.quit(); logger = null; }
	}

	/** Intended for the user to see. */
	static public void log(final String msg) {
		if (ControlWindow.isGUIEnabled() && null != logger) {
			logger.log(msg);
		} else {
			System.out.println(msg);
		}
	}

	/** Print in all printable places: log window, System.out.println, and status bar.*/
	static public void logAll(final String msg) {
		if (!ControlWindow.isGUIEnabled()) {
			System.out.println(msg);
			return;
		}
		System.out.println(msg);
		if (null != logger) logger.log(msg);
		if (null != status) status.showStatus(msg);
	}

	/** Intended for developers: prints to terminal. */
	static public void log2(String msg) {
		System.out.println(msg);
	}

	static public void setDebug(boolean debug) {
		Utils.debug = debug;
	}

	static public void setDebugMouse(boolean debug_mouse) {
		Utils.debug_mouse = debug_mouse;
	}

	static public void setDebugSQL(boolean debug_sql) {
		Utils.debug_sql = debug_sql;
	}

	/** Adjusting so that 0 is 3 o'clock, PI+PI/2 is 12 o'clock, PI is 9 o'clock, and PI/2 is 6 o'clock (why atan2 doesn't output angles this way? I remember I had the same problem for Pipe.java in the A_3D_editing plugin)
	    Using schemata in JavaAngles.ai as reference */
	static public double fixAtan2Angle(double angle) {

		double a = angle;
		//fix too large angles
		if (angle > 2*Math.PI) {
			a = angle - 2*Math.PI;
		}
		//fix signs and shity oriented angle values given by atan2
		if (a > 0.0 && a <= Math.PI/2) {
			a = Math.PI/2 - a;
		} else if (a <= 0.0 && a >= -Math.PI) {
			a = Math.PI/2 - a; // minus because angle is negative
		} else if (a > Math.PI/2 && a <= Math.PI ) {
			a = Math.PI + Math.PI + (Math.PI/2 - a);
		}

		return a;
	}

	static public int count = 0;

	/** Find out which method from which class called the method where the printCaller is used; for debugging purposes.*/
	static public void printCaller(Object called_object) {
		StackTraceElement[] elems = new Exception().getStackTrace();
		if (elems.length < 3) {
			log2("Stack trace too short! No useful info");
		} else {
			log2( "#### START TRACE ####\nObject " + called_object.getClass().getName() + " called at: " + elems[1].getFileName() + " " + elems[1].getLineNumber() + ": " + elems[1].getMethodName() + "()\n    by: " + elems[2].getClassName() + " " + elems[2].getLineNumber() + ": " + elems[2].getMethodName() + "()");
			log2("==== END ====");
		}
	}

	static public void printCaller(Object called_object, int lines) {
		StackTraceElement[] elems = new Exception().getStackTrace();
		if (elems.length < 3) {
			log2("Stack trace too short! No useful info");
		} else {
			log2( "#### START TRACE ####\nObject " + called_object.getClass().getName() + " called at: " + elems[1].getFileName() + " " + elems[1].getLineNumber() + ": " + elems[1].getMethodName() + "()\n    by: " + elems[2].getClassName() + " " + elems[2].getLineNumber() + ": " + elems[2].getMethodName() + "()");
			for (int i=3; i<lines+2 && i<elems.length; i++) {
				log2("\tby: " + elems[i].getClassName() + " " + elems[i].getLineNumber() + ": " + elems[i].getMethodName() + "()");
			}
			log2("==== END ====");
		}
	}

	static public String caller(Object called) {
		StackTraceElement[] elems = new Exception().getStackTrace();
		if (elems.length < 3) {
			log2("Stack trace too short! No useful info");
			return null;
		} else {
			return elems[2].getClassName();
		}
	}

	/**Restore ImageJ's MenuBar*/
	static public void restoreMenuBar() {
		MenuBar menu_bar = Menus.getMenuBar();
		final int n_menus = menu_bar.getMenuCount();
		for (int i=0; i<n_menus;i++) {
			Menu menu = menu_bar.getMenu(i);
			restoreMenu(menu);
		}
		//make sure there isn't a null menu bar
		//WindowManager.getCurrentWindow().setMenuBar(menu_bar);
	}

	static private void restoreMenu(final Menu menu) {
		final int n_menuitems = menu.getItemCount();
		for (int i=0; i<n_menuitems; i++) {
			MenuItem menu_item = menu.getItem(i);
			if (menu_item instanceof Menu) {
				restoreMenu((Menu)menu_item);
			}
			menu_item.setEnabled(true);
		}
	}

	static public void showMessage(String msg) {
		if (!ControlWindow.isGUIEnabled()) System.out.println(msg);
		else IJ.showMessage(msg);
	}

	/** Runs the showMessage in a separate Thread. */
	static public void showMessageT(final String msg) {
		new Thread() {
			public void run() {
				setPriority(Thread.NORM_PRIORITY);
				Utils.showMessage(msg);
			}
		}.start();
	}

	static public final void showStatus(final String msg, final boolean focus) {
		if (null == IJ.getInstance() || !ControlWindow.isGUIEnabled() || null == status) {
			System.out.println(msg);
			return;
		}
		if (focus) IJ.getInstance().toFront();

		status.showStatus(msg);
	}

	static public final void showStatus(final String msg) {
		showStatus(msg, true);
	}

	static private double last_progress = 0;
	static private int last_percent = 0;

	static public final void showProgress(final double p) {
		//IJ.showProgress(p); // never happens, can't repaint even though they are different threads
		if (null == IJ.getInstance() || !ControlWindow.isGUIEnabled() || null == status) {
			if (0 == p) {
				last_progress = 0; // reset
				last_percent = 0;
				return;
			}
			// don't show intervals smaller than 1%:
			if (last_progress + 0.01 > p ) {
				int percent = (int)(p * 100);
				if (last_percent != percent) {
					System.out.println(percent + " %");
					last_percent = percent;
				}
			}
			last_progress = p;
			return;
		}

		status.showProgress(p);
	}

	static public void debugDialog() {
		// note: all this could nicely be done using reflection, so adding another boolean variable would be automatically added here (filtering by the prefix "debug").
		GenericDialog gd = new GenericDialog("Debug:");
		gd.addCheckbox("debug", debug);
		gd.addCheckbox("debug mouse", debug_mouse);
		gd.addCheckbox("debug sql", debug_sql);
		gd.addCheckbox("debug event", debug_event);
		gd.addCheckbox("debug clip", debug_clip);
		gd.addCheckbox("debug thing", debug_thing);
		gd.showDialog();
		if (gd.wasCanceled()) {
			return;
		}
		debug = gd.getNextBoolean();
		debug_mouse = gd.getNextBoolean();
		debug_sql = gd.getNextBoolean();
		debug_event = gd.getNextBoolean();
		debug_clip = gd.getNextBoolean();
		debug_thing = gd.getNextBoolean();
	}


	/** Scan the WindowManager for open stacks.*/
	static public ImagePlus[] findOpenStacks() {
		ImagePlus[] imp = scanWindowManager("stacks");
		if (null == imp) return null;
		return imp;
	}

	/** Scan the WindowManager for non-stack images*/
	static public ImagePlus[] findOpenImages() {
		ImagePlus[] imp = scanWindowManager("images");
		if (null == imp) return null;
		return imp;
	}

	/** Scan the WindowManager for all open images, including stacks.*/
	static public ImagePlus[] findAllOpenImages() {
		return scanWindowManager("all");
	}

	static private ImagePlus[] scanWindowManager(String type) {
		// check if any stacks are opened within ImageJ
		int[] all_ids = WindowManager.getIDList();
		if (null == all_ids) return null;
		ImagePlus[] imp = new ImagePlus[all_ids.length];
		int next = 0;
		for (int i=0; i < all_ids.length; i++) {
			ImagePlus image = WindowManager.getImage(all_ids[i]);
			if (type.equals("stacks")) {
				if (image.getStackSize() <= 1) {
					continue;
				}
			} else if (type.equals("images")) {
				if (image.getStackSize() > 1) {
					continue;
				}
			}
			// add:
			imp[next] = image;
			next++;
		}
		// resize the array if necessary
		if (next != all_ids.length) {
			ImagePlus[] imp2 = new ImagePlus[next];
			System.arraycopy(imp, 0, imp2, 0, next);
			imp = imp2;
		}
		// return what has been found:
		if (0 == next) return null;
		return imp;
	}

	/**The path of the directory from which images have been recently loaded.*/
	static public String last_dir = ij.Prefs.getString(ij.Prefs.DIR_IMAGE);
	/**The path of the last opened file.*/
	static public String last_file = null;

	static public String cutNumber(double d, int n_decimals) {
		return cutNumber(d, n_decimals, false);
	}

	/** remove_trailing_zeros will leave at least one zero after the comma if appropriate. */
	static public String cutNumber(final double d, final int n_decimals, final boolean remove_trailing_zeros) {
		String num = new Double(d).toString();
		int i_e = num.indexOf("E-");
		if (-1 != i_e) {
			final int exp = Integer.parseInt(num.substring(i_e+2));
			if (n_decimals < exp) {
				final StringBuffer sb = new StringBuffer("0.");
				int count = n_decimals;
				while (count > 0) {
					sb.append('0');
					count--;
				}
				return sb.toString(); // returns 0.000... as many zeros as desired n_decimals
			}
			// else move comma
			StringBuffer sb = new StringBuffer("0.");
			int count = exp -1;
			while (count > 0) {
				sb.append('0');
				count--;
			}
			sb.append(num.charAt(0)); // the single number before the comma
			// up to here there are 'exp' number of decimals appended
			int i_end = 2 + n_decimals - exp;
			if (i_end > i_e) i_end = i_e; // there arent' that ,any, so cut
			sb.append(num.substring(2, i_end)); // all numbers after the comma
			return sb.toString();
		}
		// else, there is no scientific notation to worry about
		int i_dot = num.indexOf('.');
		StringBuffer sb = new StringBuffer(num.substring(0, i_dot+1));
		for (int i=i_dot+1; i < (n_decimals + i_dot + 1) && i < num.length(); i++) {
			sb.append(num.charAt(i));
		}
		// remove zeros from the end
		if (remove_trailing_zeros) {
			for (int i=sb.length()-1; i>i_dot+1; i--) { // leep at least one zero after the comma
				if ('0' == sb.charAt(i)) {
					sb.setLength(i);
				} else {
					break;
				}
			}
		}
		return sb.toString();
	}

	static public boolean check(String msg) {
		YesNoCancelDialog dialog = new YesNoCancelDialog(IJ.getInstance(), "Execute?", msg);
		if (dialog.yesPressed()) {
			return true;
		}
		return false;
	}

	static public boolean checkYN(String msg) {
		YesNoDialog yn = new YesNoDialog(IJ.getInstance(), "Execute?", msg);
		if (yn.yesPressed()) return true;
		return false;
	}

	static public String d2s(double d, int n_decimals) {
		return IJ.d2s(d, n_decimals);
	}

	// from utilities.c in my CurveMorphing C module ... from C! Java is a low level language with the disadvantages of the high level languages ...
	/** Returns the angle in radians of the given polar coordinates, correcting the Math.atan2 output. */
	static public double getAngle(double x, double y) {
		// calculate angle
		double a = Math.atan2(x, y);
		// fix too large angles (beats me why are they ever generated)
		if (a > 2 * Math.PI) {
			a = a - 2 * Math.PI;
		}
		// fix atan2 output scheme to match my mental scheme
		if (a >= 0.0 && a <= Math.PI/2) {
			a = Math.PI/2 - a;
		} else if (a < 0 && a >= -Math.PI) {
			a = Math.PI/2 -a;
		} else if (a > Math.PI/2 && a <= Math.PI) {
			a = Math.PI + Math.PI + Math.PI/2 - a;
		}
		// return
		return a;
	}

	static public String[] getHexRGBColor(Color color) {
		int c = color.getRGB();
		String r = Integer.toHexString(((c&0x00FF0000)>>16));
		if (1 == r.length()) r = "0" + r;
		String g = Integer.toHexString(((c&0x0000FF00)>>8));
		if (1 == g.length()) g = "0" + g;
		String b = Integer.toHexString((c&0x000000FF));
		if (1 == b.length()) b = "0" + b;
		return new String[]{r, g, b};
	}

	static public Color getRGBColorFromHex(String hex) {
		if (hex.length() < 6) return null;
		return new Color(Integer.parseInt(hex.substring(0, 2), 16), // parse in hexadecimal radix
				 Integer.parseInt(hex.substring(2, 4), 16),
				 Integer.parseInt(hex.substring(4, 6), 16));
	}

	static public final int[] get4Ints(int hex) {
		return new int[]{((hex&0xFF000000)>>24),
			         ((hex&0x00FF0000)>>16),
				 ((hex&0x0000FF00)>> 8),
				   hex&0x000000FF      };
	}

	public void run(String arg) {
		IJ.showMessage("TrakEM2", "TrakEM2 " + Utils.version + "\nCopyright Albert Cardona & Rodney Douglas\nInstitute for Neuroinformatics, Univ. Zurich / ETH\nUniversity of California Los Angeles");
	}

	static public File chooseFile(String name, String extension) {
		return Utils.chooseFile(null, name, extension);
	}

	/** Select a file from the file system, for saving purposes. Prompts for overwritting if the file exists, unless the ControlWindow.isGUIEnabled() returns false (i.e. there is no GUI). */
	static public File chooseFile(String default_dir, String name, String extension) {
		// using ImageJ's JFileChooser or internal FileDialog, according to user preferences.
		String user = System.getProperty("user.name");
		String name2 = null;
		if (null != name && null != extension) name2 = name + extension;
		else if (null != name) name2 = name;
		else if (null != extension) name2 = "untitled" + extension;
		if (null != default_dir) {
			OpenDialog.setDefaultDirectory(default_dir);
		}
		SaveDialog sd = new SaveDialog("Save",
						OpenDialog.getDefaultDirectory(),
						name2,
						extension);

		String filename = sd.getFileName();
		if (null == filename || filename.toLowerCase().startsWith("null")) return null;
		File f = new File(sd.getDirectory() + "/" + filename);
		if (f.exists() && ControlWindow.isGUIEnabled()) {
			YesNoCancelDialog d = new YesNoCancelDialog(IJ.getInstance(), "Overwrite?", "File " + filename + " exists! Overwrite?");
			if (d.cancelPressed()) {
				return null;
			} else if (!d.yesPressed()) {
				return chooseFile(name, extension);
			}
			// else if yes pressed, overwrite.
		}
		return f;
	}

	/** Returns null or the selected directory and file. */
	static public String[] selectFile(String title_msg) {
		OpenDialog od = new OpenDialog("Select file", OpenDialog.getDefaultDirectory(), null);
		String file = od.getFileName();
		if (null == file || file.toLowerCase().startsWith("null")) return null;
		String dir = od.getDirectory();
		File f = null;
		if (null != dir) {
			dir = dir.replace('\\', '/');
			if (!dir.endsWith("/")) dir += "/";
			f = new File(dir + "/" + file); // I'd use File.separator, but in Windows it fails
		}
		if (null == dir || !f.exists()) {
			Utils.log2("No proper file selected.");
			return null;
		}
		return new String[]{dir, file};
	}

	static public boolean saveToFile(File f, String contents) {
		if (null == f) return false;
		try {
			/*
			DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), contents.length()));
			*/
			OutputStreamWriter dos = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(f), contents.length()), "8859_1"); // encoding in Latin 1 (for macosx not to mess around
			//dos.writeBytes(contents);
			dos.write(contents, 0, contents.length());
			dos.flush();
		} catch (Exception e) {
			IJError.print(e);
			Utils.showMessage("ERROR: Most likely did NOT save your file.");
			return false;
		}
		return true;
	}

	static public boolean saveToFile(String name, String extension, String contents) {
		if (null == contents) {
			Utils.log2("Utils.saveToFile: contents is null");
			return false;
		}
		// save the file
		File f = Utils.chooseFile(name, extension);
		return Utils.saveToFile(f, contents);
	}

	/** Converts sequences of spaces into single space, and trims the ends. */
	static public String cleanString(String s) {
		s = s.trim();
		while (-1 != s.indexOf("\u0020\u0020")) { // \u0020 equals a single space
			s = s.replaceAll("\u0020\u0020", "\u0020");
		}
		return s;
	}

	static public final String openTextFile(final String path) {
		if (null == path || !new File(path).exists()) return null;
		final StringBuffer sb = new StringBuffer();
		BufferedReader r = null;
		try {
			r = new BufferedReader(new FileReader(path));
			while (true) {
				String s = r.readLine();
				if (null == s) break;
				sb.append(s).append('\n'); // I am sure the reading can be done better
        		}
		} catch (Exception e) {
			IJError.print(e);
			if (null != r) try { r.close(); } catch (IOException ioe) { ioe.printStackTrace(); }
			return null;
		}
		return sb.toString();
	}

	/** Returns the file found at path as an array of lines, or null if not found. */
	static public final String[] openTextFileLines(final String path) {
		if (null == path || !new File(path).exists()) return null;
		final ArrayList al = new ArrayList();
		try {
			BufferedReader r = new BufferedReader(new FileReader(path));
			while (true) {
				String s = r.readLine();
				if (null == s) break;
				al.add(s);
        		}
			r.close();
		} catch (Exception e) {
			IJError.print(e);
			return null;
		}
		final String[] sal = new String[al.size()];
		al.toArray(sal);
		return sal;
	}

	static public final char[] openTextFileChars(final String path) {
		File f = null;
		if (null == path || !(f = new File(path)).exists()) {
			Utils.log("File not found: " + path);
			return null;
		}
		final char[] src = new char[(int)f.length()]; // assumes file is small enough to fit in integer range!
		try {
			BufferedReader r = new BufferedReader(new FileReader(path));
			r.read(src, 0, src.length);
			r.close();
		} catch (Exception e) {
			IJError.print(e);
			return null;
		}
		return src;
	}

	/** The cosinus between two vectors (in polar coordinates), by means of the dot product. */
	static public final double getCos(final double x1, final double y1, final double x2, final double y2) {
		return (x1 * x2 + y1 * y2) / (Math.sqrt(x1*x1 + y1*y1) * Math.sqrt(x2*x2 + y2*y2));
	}

	static public final String removeExtension(final String path) {
		final int i_dot = path.lastIndexOf('.');
		if (-1 == i_dot || i_dot + 4 != path.length()) return path;
		else return path.substring(0, i_dot);
	}

	/** A helper for GenericDialog checkboxes to control other the enabled state of other GUI elements in the same dialog. */
	static public final void addEnablerListener(final Checkbox master, final Component[] enable, final Component[] disable) {
		master.addItemListener(new ItemListener() {
			public void itemStateChanged(ItemEvent ie) {
				if (ie.getStateChange() == ItemEvent.SELECTED) {
					process(enable, true);
					process(disable, false);
				} else {
					process(enable, false);
					process(disable, true);
				}
			}
			private void process(final Component[] c, final boolean state) {
				if (null == c) return;
				for (int i=0; i<c.length; i++) c[i].setEnabled(state);
			}
		});
	}

	static private int n_CPUs = 0;

	/** This method is obsolete: there's Runtime.getRuntime().availableProcessors() */
	/*
	static public final int getCPUCount() {
		if (0 != n_CPUs) return n_CPUs;
		if (IJ.isWindows()) return 1; // no clue
		// POSIX systems, attempt to count CPUs from /proc/stat
		try {
			Runtime runtime = Runtime.getRuntime();
			Process process = runtime.exec("cat /proc/stat");
			InputStream is = process.getInputStream();
			InputStreamReader isr = new InputStreamReader(is);
			BufferedReader br = new BufferedReader(isr);
			String line;
			n_CPUs = 0;
			// valid cores will print as cpu0, cpu1. cpu2 ...
			while ((line = br.readLine()) != null) {
				if (0 == line.indexOf("cpu") && line.length() > 3 && Character.isDigit(line.charAt(3))) {
					n_CPUs++;
				}
			}
			// fix possible errors
			if (0 == n_CPUs) n_CPUs = 1;
			return n_CPUs;
		} catch (Exception e) {
			Utils.log(e.toString()); // just one line
			return 1;
		}
	}
	*/

	static public final boolean wrongImageJVersion() {
		boolean b = IJ.versionLessThan("1.37g");
		if (b) Utils.showMessage("TrakEM2 requires ImageJ 1.37g or above.");
		return b;
	}

	static public final boolean java3d = isJava3DInstalled();

	static private final boolean isJava3DInstalled() {
		try {
			Class p3f = Class.forName("javax.vecmath.Point3f");
		} catch (ClassNotFoundException cnfe) {
			return false;
		}
		return true;
	}

	static public final void addLayerRangeChoices(final Layer selected, final GenericDialog gd) {
		Utils.addLayerRangeChoices(selected, selected, gd);
	}

	static public final void addLayerRangeChoices(final Layer first, final Layer last, final GenericDialog gd) {
		final String[] layers = new String[first.getParent().size()];
		final ArrayList al_layer_titles =  new ArrayList();
		int i = 0;
		for (Iterator it = first.getParent().getLayers().iterator(); it.hasNext(); ) {
			layers[i] = first.getProject().findLayerThing((Layer)it.next()).toString();
			al_layer_titles.add(layers[i]);
			i++;
		}
		final int i_first = first.getParent().indexOf(first);
		final int i_last = last.getParent().indexOf(last);
		gd.addChoice("Start: ", layers, layers[i_first]);
		final Vector v = gd.getChoices();
		final Choice cstart = (Choice)v.get(v.size()-1);
		gd.addChoice("End: ", layers, layers[i_last]);
		final Choice cend = (Choice)v.get(v.size()-1);
		cstart.addItemListener(new ItemListener() {
			public void itemStateChanged(ItemEvent ie) {
				int index = al_layer_titles.indexOf(ie.getItem());
				if (index > cend.getSelectedIndex()) cend.select(index);
			}
		});
		cend.addItemListener(new ItemListener() {
			public void itemStateChanged(ItemEvent ie) {
				int index = al_layer_titles.indexOf(ie.getItem());
				if (index < cstart.getSelectedIndex()) cstart.select(index);
			}
		});
	}

	static public final void addLayerChoice(final String label, final Layer selected, final GenericDialog gd) {
		final String[] layers = new String[selected.getParent().size()];
		final ArrayList al_layer_titles =  new ArrayList();
		int i = 0;
		for (Iterator it = selected.getParent().getLayers().iterator(); it.hasNext(); ) {
			layers[i] = selected.getProject().findLayerThing((Layer)it.next()).toString();
			al_layer_titles.add(layers[i]);
			i++;
		}
		final int i_layer = selected.getParent().indexOf(selected);
		gd.addChoice(label, layers, layers[i_layer]);

	}

	/** Converts the ImageProcessor to an ImageProcessor of the given type, or the same if of equal type. */
	static final public ImageProcessor convertTo(final ImageProcessor ip, final int type, final boolean scaling) {
		switch (type) {
			case ImagePlus.GRAY8:
				return ip.convertToByte(scaling);
			case ImagePlus.GRAY16:
				return ip.convertToShort(scaling);
			case ImagePlus.GRAY32:
				return ip.convertToFloat();
			case ImagePlus.COLOR_RGB:
				return ip.convertToRGB();
			case ImagePlus.COLOR_256:
				ImagePlus imp = new ImagePlus("", ip.convertToRGB());
				new ImageConverter(imp).convertRGBtoIndexedColor(256);
				return imp.getProcessor();
			default:
				return null;
		}
	}

	/** Will make a new double[] array, then fit in it as many points from the given array as possible according to the desired new length. If the new length is shorter that a.length, it will shrink and crop from the end; if larger, the extra spaces will be set with zeros. */
	static public final double[] copy(final double[] a, final int new_length) {
		final double[] b = new double[new_length];
		final int len = a.length > new_length ? new_length : a.length; 
		System.arraycopy(a, 0, b, 0, len);
		return b;
	}

	/** Reverse in place an array of doubles. */
	static public final void reverse(final double[] a) {
		for (int left=0, right=a.length-1; left<right; left++, right--) {
			double tmp = a[left];
			a[left] = a[right];
			a[right] = tmp;
		}
	}

	/** OS-agnostic diagnosis of whether the click was for the contextual popup menu. */
	static public final boolean isPopupTrigger(final MouseEvent me) {
		return me.isPopupTrigger() || MouseEvent.BUTTON2 == me.getButton() || 0 != (me.getModifiers() & Event.META_MASK);
	}

	/** Repaint the given Component on the swing repaint thread (aka "SwingUtilities.invokeLater"). */
	static public final void updateComponent(final Component c) {
		//c.invalidate();
		//c.validate();
		// ALL that was needed: to put it into the swing repaint queue ... couldn't they JUST SAY SO
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				c.repaint();
			}
		});
	}
	/** Like calling pack() on a Frame but on a Component. */
	static public final void revalidateComponent(final Component c) {
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				c.invalidate();
				c.validate();
				c.repaint();
			}
		});
	}

	static public final Point2D.Double transform(final AffineTransform affine, final double x, final double y) {
		final Point2D.Double pSrc = new Point2D.Double(x, y);
		if (affine.isIdentity()) return pSrc;
		final Point2D.Double pDst = new Point2D.Double();
		affine.transform(pSrc, pDst);
		return pDst;
	}
	static public final Point2D.Double inverseTransform(final AffineTransform affine, final double x, final double y) {
		final Point2D.Double pSrc = new Point2D.Double(x, y);
		if (affine.isIdentity()) return pSrc;
		final Point2D.Double pDst = new Point2D.Double();
		try {
			affine.createInverse().transform(pSrc, pDst);
		} catch (NoninvertibleTransformException nite) {
			IJError.print(nite);
		}
		return pDst;
	}

	/** Returns the time as HH:MM:SS */
	static public final String now() {
		/* Java time management is retarded. */
		final Calendar c = Calendar.getInstance();
		final int hour = c.get(Calendar.HOUR_OF_DAY);
		final int min = c.get(Calendar.MINUTE);
		final int sec = c.get(Calendar.SECOND);
		final StringBuffer sb = new StringBuffer();
		if (hour < 10) sb.append('0');
		sb.append(hour).append(':');
		if (min < 10) sb.append('0');
		sb.append(min).append(':');
		if (sec < 10) sb.append(0);
		return sb.append(sec).toString();
	}

	static public final void sleep(final long miliseconds) {
		try { Thread.currentThread().sleep(miliseconds); } catch (Exception e) { e.printStackTrace(); }
	}

	/** Mix colors visually: red + green = yellow, etc.*/
	static public final Color mix(Color c1, Color c2) {
		final float[] b = Color.RGBtoHSB(c1.getRed(), c1.getGreen(), c1.getBlue(), new float[3]);
		final float[] c = Color.RGBtoHSB(c2.getRed(), c2.getGreen(), c2.getBlue(), new float[3]);
		final float[] a = new float[3];
		// find to which side the hue values are closer, since hue space is a a circle
		// hue values all between 0 and 1
		float h1 = b[0];
		float h2 = c[0];
		if (h1 < h2) {
			float tmp = h1;
			h1 = h2;
			h2 = tmp;
		}
		float d1 = h2 - h1;
		float d2 = 1 + h1 - h2;
		if (d1 < d2) {
			a[0] = h1 + d1 / 2;
		} else {
			a[0] = h2 + d2 / 2;
			if (a[0] > 1) a[0] -= 1;
		}

		for (int i=1; i<3; i++) a[i] = (b[i] + c[i]) / 2; // only Saturation and Brightness can be averaged
		return Color.getHSBColor(a[0], a[1], a[2]);
	}

	/** Test whether the areas intersect each other. */
	static public final boolean intersects(final Area a1, final Area a2) {
		final Area b = new Area(a1);
		b.intersect(a2);
		final java.awt.Rectangle r = b.getBounds();
		return 0 != r.width && 0 != r.height;
	}

	/** 1 A, 2 B, 3 C  ---  26 - z, 27 AA, 28 AB, 29 AC  --- 26*27 AAA */
	static public final String getCharacter(int i) {
		i--;
		int k = i / 26;
		char c = (char)((i % 26) + 65); // 65 is 'A'
		if (0 == k) return Character.toString(c);
		return new StringBuffer().append(getCharacter(k)).append(c).toString();
	}

	static private Field shape_field = null;

	static public final Shape getShape(final ShapeRoi roi) {
		try {
			if (null == shape_field) {
				shape_field = ShapeRoi.class.getDeclaredField("shape");
				shape_field.setAccessible(true);
			}
			return (Shape)shape_field.get((ShapeRoi)roi);
		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
	}

	/** Get by reflection a private or protected field in the given object. */
	static public final Object getField(final Object ob, final String field_name) {
		if (null == ob || null == field_name) return null;
		try {
			Field f = ob.getClass().getDeclaredField(field_name);
			f.setAccessible(true);
			return f.get(ob);
		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
	}

	static public final Area getArea(final Roi roi) {
		if (null == roi) return null;
		ShapeRoi sroi = new ShapeRoi(roi);
		AffineTransform at = new AffineTransform();
		Rectangle bounds = sroi.getBounds();
		at.translate(bounds.x, bounds.y);
		Area area = new Area(getShape(sroi));
		return area.createTransformedArea(at);
	}

	/** Returns the approximated area of the given Area object. */
	static public final double measureArea(Area area, final Loader loader) {
		double sum = 0;
		try {
			Rectangle bounds = area.getBounds();
			double scale = 1;
			if (bounds.width > 2048 || bounds.height > 2048) {
				scale = 2048.0 / bounds.width;
			}
			if (0 == scale) {
				Utils.log("Can't measure: area too large, out of scale range for approximation.");
				return sum;
			}
			AffineTransform at = new AffineTransform();
			at.translate(-bounds.x, -bounds.y);
			at.scale(scale, scale);
			area = area.createTransformedArea(at);
			bounds = area.getBounds();
			if (0 == bounds.width || 0 == bounds.height) {
				Utils.log("Can't measure: area too large, approximates zero.");
				return sum;
			}
			if (null != loader) loader.releaseToFit(bounds.width * bounds.height * 3);
			BufferedImage bi = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_BYTE_INDEXED);
			Graphics2D g = bi.createGraphics();
			g.setColor(Color.white);
			g.fill(area);
			final byte[] pixels = ((DataBufferByte)bi.getRaster().getDataBuffer()).getData(); // buffer.getData();
			for (int i=pixels.length-1; i>-1; i--) {
				//if (255 == (pixels[i]&0xff)) sum++;
				if (0 != pixels[i]) sum++;
			}
			bi.flush();
			g.dispose();
			if (1 != scale) sum = sum / (scale * scale);
		} catch (Throwable e) {
			IJError.print(e);
		}
		return sum;
	}

	/** Compute the area of the triangle defined by 3 points in 3D space, returning half of the length of the vector resulting from the cross product of vectors p1p2 and p1p3. */
	static public final double measureArea(final Point3f p1, final Point3f p2, final Point3f p3) {
		final Vector3f v = new Vector3f();
		v.cross(new Vector3f(p2.x - p1.x, p2.y - p1.y, p2.z - p1.z),
			new Vector3f(p3.x - p1.x, p3.y - p1.y, p3.z - p1.z));
		return 0.5 * Math.abs(v.x * v.x + v.y * v.y + v.z * v.z);
	}

	/** A method that circumvents the findMinAndMax when creating a float processor from an existing processor.  Ignores color calibrations and does no scaling at all. */
	static public final FloatProcessor fastConvertToFloat(final ByteProcessor ip) {
		final byte[] pix = (byte[])ip.getPixels();
		final float[] data = new float[pix.length];
		for (int i=0; i<pix.length; i++) data[i] = pix[i]&0xff;
		final FloatProcessor fp = new FloatProcessorT2(ip.getWidth(), ip.getHeight(), data, ip.getColorModel(), ip.getMin(), ip.getMax());
		return fp;
	}
	/** A method that circumvents the findMinAndMax when creating a float processor from an existing processor.  Ignores color calibrations and does no scaling at all. */
	static public final FloatProcessor fastConvertToFloat(final ShortProcessor ip) {
		final short[] pix = (short[])ip.getPixels();
		final float[] data = new float[pix.length];
		for (int i=0; i<pix.length; i++) data[i] = pix[i]&0xffff;
		final FloatProcessor fp = new FloatProcessorT2(ip.getWidth(), ip.getHeight(), data, ip.getColorModel(), ip.getMin(), ip.getMax());
		return fp;
	}
	/** A method that circumvents the findMinAndMax when creating a float processor from an existing processor.  Ignores color calibrations and does no scaling at all. */
	static public final FloatProcessor fastConvertToFloat(final ImageProcessor ip, final int type) {
		switch (type) {
			case ImagePlus.GRAY16: return fastConvertToFloat((ShortProcessor)ip);
			case ImagePlus.GRAY32: return (FloatProcessor)ip;
			case ImagePlus.GRAY8:
			case ImagePlus.COLOR_256: return fastConvertToFloat((ByteProcessor)ip);
			case ImagePlus.COLOR_RGB: return (FloatProcessor)ip.convertToFloat(); // SLOW
		}
		return null;
	}
	static public final FloatProcessor fastConvertToFloat(final ImageProcessor ip) {
		if (ip instanceof ByteProcessor) return fastConvertToFloat((ByteProcessor)ip);
		if (ip instanceof ShortProcessor) return fastConvertToFloat((ShortProcessor)ip);
		return (FloatProcessor)ip.convertToFloat();
	}

	/** Creates a new ResultsTable with the given window title and column titles, and 2 decimals of precision, or if one exists for the given window title, returns it. */
	static public final ResultsTable createResultsTable(final String title, final String[] columns) {
		TextWindow tw = (TextWindow)WindowManager.getFrame(title);
		if (null != tw) {
			// hacking again ... missing a getResultsTable() method in TextWindow
			ResultsTable rt = (ResultsTable)Utils.getField(tw.getTextPanel(), "rt");
			if (null != rt) return rt; // assumes columns will be identical
		}
		// else create a new one
		ResultsTable rt = new ResultsTable();
		rt.setPrecision(2);
		for (int i=0; i<columns.length; i++) rt.setHeading(i, columns[i]);
		//
		return rt;
	}
}
