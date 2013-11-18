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

*/

package ini.trakem2.utils;

import ij.IJ;
import ij.ImagePlus;
import ij.Menus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.OvalRoi;
import ij.gui.YesNoCancelDialog;
import ij.io.OpenDialog;
import ij.io.SaveDialog;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageConverter;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import ij.text.TextWindow;
import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Pipe;
import ini.trakem2.display.YesNoDialog;
import ini.trakem2.imaging.FloatProcessorT2;
import ini.trakem2.persistence.Loader;
import ini.trakem2.plugin.TPlugIn;
import ini.trakem2.tree.ProjectThing.Profile_List;
import ini.trakem2.vector.VectorString3D;

import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Event;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Image;
import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuItem;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/** Utils class: stores generic widely used methods. In particular, those for logging text messages (for debugging) and also some math and memory utilities.
 *
 *
 */
public class Utils implements ij.plugin.PlugIn {

	static public String version = "1.0a 2012-07-04";

	static public boolean debug = false;
	static public boolean debug_mouse = false;
	static public boolean debug_sql = false;
	static public boolean debug_event = false;
	static public boolean debug_clip = false; //clip for repainting
	static public boolean debug_thing = false;

    static private PrintStream printer = System.out;

	/** The error to use in floating-point or double floating point literal comparisons. */
	static public final double FL_ERROR = 0.0000001;

	static public void debug(String msg) {
		if (debug) IJ.log(msg);
	}

	static public void debugMouse(String msg) {
		if (debug_mouse) IJ.log(msg);
	}

	/** Avoid waiting on the AWT thread repainting ImageJ's log window. */
	static private final class LogDispatcher extends Thread {
		private final StringBuilder cache = new StringBuilder();
		public LogDispatcher(ThreadGroup tg) {
			super(tg, "T2-Log-Dispatcher");
			setPriority(Thread.NORM_PRIORITY);
			try { setDaemon(true); } catch (Exception e) { e.printStackTrace(); }
			start();
		}
		public final void quit() {
			interrupt();
			synchronized (cache) { cache.notify(); }
		}
		public final void log(final String msg) {
			try {
				synchronized (cache) {
					cache.append(msg).append('\n');
					cache.notify();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		public void run() {
			final StringBuilder sb = new StringBuilder();
			while (!isInterrupted()) {
				try {
					final long start = System.currentTimeMillis();
					// Accumulate messages for about one second
					do {
						synchronized (cache) {
							if (cache.length() > 0) {
								sb.append(cache);
								cache.setLength(0);
							}
						}
						try { Thread.sleep(100); } catch (InterruptedException ie) { return; }
					} while (System.currentTimeMillis() - start < 1000); 

					// ... then, if any, update the log window:
					if (sb.length() > 0) {
						IJ.log(sb.toString());
						sb.setLength(0);
					}
					synchronized (cache) {
						if (0 == cache.length()) {
							try { cache.wait(); } catch (InterruptedException ie) {}
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	/** Avoid waiting on the AWT thread repainting ImageJ's status bar.
	    Waits 100 ms before printing the status message; if too many status messages are being sent, the last one overrides all. */
	static private final class StatusDispatcher extends Thread {
		private volatile String msg = null;
		private volatile double progress = -1;
		public StatusDispatcher(ThreadGroup tg) {
			super(tg, "T2-Status-Dispatcher");
			setPriority(Thread.NORM_PRIORITY);
			try { setDaemon(true); } catch (Exception e) { e.printStackTrace(); }
			start();
		}
		public final void quit() {
			interrupt();
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
			while (!isInterrupted()) {
				try {
					String msg = null;
					double progress = -1;
					synchronized (this) {
						// Acquire and reset
						if (null != this.msg) {
							msg = this.msg;
							this.msg = null;
						}
						if (-1 != this.progress) {
							progress = this.progress;
							this.progress = -1;
						}
					}

					// Execute within the context of this Thread
					if (null != msg) {
						IJ.showStatus(msg);
						msg = null;
					}
					if (-1 != progress) IJ.showProgress(progress);

					// allow some time for overwriting of messages
					Thread.sleep(100);
					synchronized (this) {
						if (null == this.msg && -1 == this.progress) {
							try { wait(); } catch (InterruptedException ie) {}
						}
					}
				} catch (InterruptedException ie) {
					// pass
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}
	static private LogDispatcher logger = null; 
	static private StatusDispatcher status = null;

	/** Initialize house keeping threads. */
	static public final void setup(final ControlWindow master) { // the ControlWindow acts as a switch: nobody can controls this because the CW constructor is private
		SwingUtilities.invokeLater(new Runnable() { public void run() {
			if (null != status) status.quit();
			if (null != logger) logger.quit();
			logger = new LogDispatcher(Thread.currentThread().getThreadGroup());
			status = new StatusDispatcher(Thread.currentThread().getThreadGroup());
		}});
	}

	/** Destroy house keeping threads. */
	static public final void destroy(final ControlWindow master) {
		if (null != status) { status.quit(); status = null; }
		if (null != logger) { logger.quit(); logger = null; }
	}

    static public synchronized void setLogStream(final PrintStream ps)
    {
        printer = ps;
    }

    static public PrintStream getLogStream()
    {
        return printer;
    }

	/** Intended for the user to see. */
	static public final void log(final String msg) {
		if (ControlWindow.isGUIEnabled() && null != logger) {
			logger.log(msg);
		} else {
			printer.println(msg);
		}
	}
	
	/** Intended for the user to see; time-stamps every logging line. */
	static public final void logStamped(final String msg) {
		if (ControlWindow.isGUIEnabled() && null != logger) {
			logger.log(new Date().toString() + " : " + msg);
		} else {
			printer.println(new Date().toString() + " : " + msg);
		}
	}

	/** Print in all printable places: log window, System.out.println, and status bar.*/
	static public final void logAll(final String msg) {
		if (!ControlWindow.isGUIEnabled()) {
			printer.println(msg);
			return;
		}
		printer.println(msg);
		if (null != IJ.getInstance() && null != logger) logger.log(msg);
		if (null != status) status.showStatus(msg);
	}

	/** Intended for developers: prints to terminal. */
	static public final void log2(final String msg) {
		System.out.println(msg);
	}

	/** Pretty-print the object, for example arrays as [0, 1, 2]. */
	static public final void log2(final Object ob) {
		Utils.log2(null, ob);
	}
	/** Pretty-print the object, for example arrays as [0, 1, 2]. */
	static public final void log2(final String msg, final Object ob) {
		Utils.log2((null != msg ? msg : "") + ob + "\n\t" + Utils.toString(ob) + "\n");
	}
	static public final void log2(final String msg, final Object ob1, final Object... ob) {
		final StringBuilder sb = new StringBuilder(null == msg ? "" : msg + "\n");
		sb.append(ob1.toString()).append(" : ").append(Utils.toString(ob1)).append('\n');
		for (int i=0; i<ob.length; i++) sb.append(ob.toString()).append(" : ").append(Utils.toString(ob[i])).append('\n');
		sb.setLength(sb.length()-1);
		Utils.log2(sb.toString());
	}

	static public final void log(final Object ob) {
		Utils.log(Utils.toString(ob));
	}
	
	/** Pretty-print the object, for example arrays as [0, 1, 2]. */
	static public final void log(final String msg, final Object ob) {
		Utils.log((null != msg ? msg : "") + "\n\t" + Utils.toString(ob));
	}

	static public final void log2(final Object... ob){
		Utils.log2(Utils.toString(ob));
	}
	static public final void logMany2(final Object... ob){
		Utils.log2(Utils.toString(ob));
	}

	/** Print an object; if it's an array, print each element, recursively, as [0, 1, 2] or [[0, 1, 2], [3, 4, 5]], etc, same for Iterable and Map objects. */
	static public final String toString(final Object ob) {
		if (null == ob) return "null";
		// Clojure could do this so much easier with a macro
		final StringBuilder sb = new StringBuilder();
		sb.append('[');
		char closing = ']';
		if (ob instanceof String[]) { // could be done with Object[] and recursive calls, but whatever
			final String[] s = (String[])ob;
			for (int i=0; i<s.length; i++) sb.append(s[i]).append(", ");
		} else if (ob instanceof int[]) {
			final int[] s = (int[])ob;
			for (int i=0; i<s.length; i++) sb.append(s[i]).append(", ");
		} else if (ob instanceof double[]) {
			final double[] s = (double[])ob;
			for (int i=0; i<s.length; i++) sb.append(s[i]).append(", ");
		} else if (ob instanceof float[]) {
			final float[] s = (float[])ob;
			for (int i=0; i<s.length; i++) sb.append(s[i]).append(", ");
		} else if (ob instanceof char[]) {
			final char[] s = (char[])ob;
			for (int i=0; i<s.length; i++) sb.append(s[i]).append(", ");
		} else if (ob instanceof Object[]) {
			final Object[] s = (Object[])ob;
			for (int i=0; i<s.length; i++) sb.append(Utils.toString(s[i])).append(", ");
		} else if (ob instanceof Iterable<?>) {
			final Iterable<?> s = (Iterable<?>)ob;
			for (Iterator<?> it = s.iterator(); it.hasNext(); ) sb.append(Utils.toString(it.next())).append(", ");
		} else if (ob instanceof Map<?,?>) {
			sb.setCharAt(0, '{');
			closing = '}';
			final Map<?,?> s = (Map<?,?>)ob;
			for (final Map.Entry<?,?> e : s.entrySet()) {
				sb.append(Utils.toString(e.getKey())).append(" => ").append(Utils.toString(e.getValue())).append(", ");
			}
		} else if (ob instanceof long[]) {
			final long[] s = (long[])ob;
			for (int i=0; i<s.length; i++) sb.append(s[i]).append(", ");
		} else if (ob instanceof short[]) {
			final short[] s = (short[])ob;
			for (int i=0; i<s.length; i++) sb.append(s[i]).append(", ");
		} else if (ob instanceof boolean[]) {
			final boolean[] s = (boolean[])ob;
			for (int i=0; i<s.length; i++) sb.append(s[i]).append(", ");
		} else {
			return ob.toString();
		}
		final int len = sb.length();
		if (len > 2) sb.setLength(len-2); // remove the last ", "
		sb.append(closing);
		sb.append('\n');
		return sb.toString();
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

	/** Find out which method from which class called the method where the printCaller is used; for debugging purposes.*/
	static public final void printCaller(Object called_object) {
		StackTraceElement[] elems = new Exception().getStackTrace();
		if (elems.length < 3) {
			log2("Stack trace too short! No useful info");
		} else {
			log2( "#### START TRACE ####\nObject " + called_object.getClass().getName() + " called at: " + elems[1].getFileName() + " " + elems[1].getLineNumber() + ": " + elems[1].getMethodName() + "()\n    by: " + elems[2].getClassName() + " " + elems[2].getLineNumber() + ": " + elems[2].getMethodName() + "()");
			log2("==== END ====");
		}
	}

	static public final void printCaller(Object called_object, int lines) {
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

	/** Returns a String representation of the class of the object one step before in the stack trace. */
	static public final String caller(Object called) {
		StackTraceElement[] elems = new Exception().getStackTrace();
		if (elems.length < 3) {
			log2("Stack trace too short! No useful info");
			return null;
		} else {
			return elems[2].getClassName();
		}
	}

	/**Restore ImageJ's MenuBar*/
	static public final void restoreMenuBar() {
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

	static public final void showMessage(String msg) {
		if (!ControlWindow.isGUIEnabled()) printer.println(msg);
		else IJ.showMessage(msg);
	}
	static public final void showMessage(String title, String msg) {
		if (!ControlWindow.isGUIEnabled()) printer.println(title + "\n" + msg);
		else IJ.showMessage(title, msg);
	}

	/** Runs the showMessage in a separate Thread. */
	static public final void showMessageT(final String msg) {
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
		showStatus(msg, false);
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
					printer.println(percent + " %");
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
	static public final String cutNumber(final double d, final int n_decimals, final boolean remove_trailing_zeros) {
		final String num = new Double(d).toString();
		int i_e = num.indexOf("E-");
		if (-1 != i_e) {
			final int exp = Integer.parseInt(num.substring(i_e+2));
			if (n_decimals < exp) {
				final StringBuilder sb = new StringBuilder("0.");
				int count = n_decimals;
				while (count > 0) {
					sb.append('0');
					count--;
				}
				return sb.toString(); // returns 0.000... as many zeros as desired n_decimals
			}
			// else move comma
			final StringBuilder sb = new StringBuilder("0.");
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
		final int i_dot = num.indexOf('.');
		final StringBuilder sb = new StringBuilder(num.substring(0, i_dot+1));
		for (int i=i_dot+1; i < (n_decimals + i_dot + 1) && i < num.length(); i++) {
			sb.append(num.charAt(i));
		}
		// remove zeros from the end
		if (remove_trailing_zeros) {
			for (int i=sb.length()-1; i>i_dot+1; i--) { // leave at least one zero after the comma
				if ('0' == sb.charAt(i)) {
					sb.setLength(i);
				} else {
					break;
				}
			}
		}
		return sb.toString();
	}
	
	/** Zero-pad a number, so that '1' becomes '001' if n_digits is 3. */
	static public final String zeroPad(final int i, final int n_digits) {
		final StringBuilder sb = new StringBuilder();
		sb.append(i);
		int len = sb.length();
		while (len < n_digits) {
			sb.insert(0, '0');
			len++;
		}
		return sb.toString();
	}

	static public final boolean check(final String msg) {
		try { return new TaskOnEDT<Boolean>(new Callable<Boolean>() { public Boolean call() {
		YesNoCancelDialog dialog = new YesNoCancelDialog(IJ.getInstance(), "Execute?", msg);
		if (dialog.yesPressed()) {
			return true;
		}
		return false;
		}}).get(); } catch (Throwable t) { IJError.print(t); return false; }
	}

	static public final boolean checkYN(final String msg) {
		try { return new TaskOnEDT<Boolean>(new Callable<Boolean>() { public Boolean call() {
		YesNoDialog yn = new YesNoDialog(IJ.getInstance(), "Execute?", msg);
		if (yn.yesPressed()) return true;
		return false;
		}}).get(); } catch (Throwable t) { IJError.print(t); return false; }
	}

	static public final String d2s(double d, int n_decimals) {
		return IJ.d2s(d, n_decimals);
	}

	static public final String[] getHexRGBColor(Color color) {
		int c = color.getRGB();
		String r = Integer.toHexString(((c&0x00FF0000)>>16));
		if (1 == r.length()) r = "0" + r;
		String g = Integer.toHexString(((c&0x0000FF00)>>8));
		if (1 == g.length()) g = "0" + g;
		String b = Integer.toHexString((c&0x000000FF));
		if (1 == b.length()) b = "0" + b;
		return new String[]{r, g, b};
	}
	
	static public final String asHexRGBColor(final Color color) {
		final StringBuilder sb = new StringBuilder(6);
		Utils.asHexRGBColor(sb, color);
		return sb.toString();
	}
	static public final void asHexRGBColor(final StringBuilder sb, final Color color) {
		final int c = color.getRGB();
		String r = Integer.toHexString(((c&0x00FF0000)>>16));
		if (1 == r.length()) sb.append('0');
		sb.append(r);
		String g = Integer.toHexString(((c&0x0000FF00)>>8));
		if (1 == g.length()) sb.append('0');
		sb.append(g);
		String b = Integer.toHexString((c&0x000000FF));
		if (1 == b.length()) sb.append('0');
		sb.append(b);
	}

	static public final Color getRGBColorFromHex(final String hex) {
		if (hex.length() < 6) return null;
		return new Color(Integer.parseInt(hex.substring(0, 2), 16), // parse in hexadecimal radix
				 Integer.parseInt(hex.substring(2, 4), 16),
				 Integer.parseInt(hex.substring(4, 6), 16));
	}

	static public final int[] get4Ints(final int hex) {
		return new int[]{((hex&0xFF000000)>>24),
			         ((hex&0x00FF0000)>>16),
				 ((hex&0x0000FF00)>> 8),
				   hex&0x000000FF      };
	}

	public void run(String arg) {
		try { new TaskOnEDT<Boolean>(new Callable<Boolean>() { public Boolean call() {
		IJ.showMessage("TrakEM2",
				new StringBuilder("TrakEM2 ").append(Utils.version)
				.append("\nCopyright Albert Cardona & Rodney Douglas\n")
				.append("Institute for Neuroinformatics, Univ/ETH Zurich.\n")
				.append("\nRegistration library copyright Stephan Saalfeld, MPI-CBG.\n")
				.append("\nLens correction copyright Verena Kaynig, ETH Zurich.\n")
				.append("\nSome parts copyright Ignacio Arganda, INI Univ/ETH Zurich.")
				.toString());
		return true;
		}}).get(); } catch (Throwable t) { IJError.print(t); }
	}

	static public final File chooseFile(String name, String extension) {
		return Utils.chooseFile(null, name, extension);
	}

	/** Select a file from the file system, for saving purposes. Prompts for overwritting if the file exists, unless the ControlWindow.isGUIEnabled() returns false (i.e. there is no GUI). */
	static public final File chooseFile(final String default_dir, final String name, final String extension) {
		try { return new TaskOnEDT<File>(new Callable<File>() { public File call() {
		// using ImageJ's JFileChooser or internal FileDialog, according to user preferences.
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
		String dir = sd.getDirectory();
		if (IJ.isWindows()) dir = dir.replace('\\', '/');
		if (!dir.endsWith("/")) dir += "/";
		File f = new File(dir + filename);
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
		}}).get(); } catch (Throwable t) { IJError.print(t); return null; }
	}

	/** Returns null or the selected directory and file. */
	static public final String[] selectFile(String title_msg) {
		try { return new TaskOnEDT<String[]>(new Callable<String[]>() { public String[] call() {
		OpenDialog od = new OpenDialog("Select file", OpenDialog.getDefaultDirectory(), null);
		String file = od.getFileName();
		if (null == file || file.toLowerCase().startsWith("null")) return null;
		String dir = od.getDirectory();
		File f = null;
		if (null != dir) {
			if (IJ.isWindows()) dir = dir.replace('\\', '/');
			if (!dir.endsWith("/")) dir += "/";
			f = new File(dir + file); // I'd use File.separator, but in Windows it fails
		}
		if (null == dir || !f.exists()) {
			Utils.log2("No proper file selected.");
			return null;
		}
		return new String[]{dir, file};
		}}).get(); } catch (Throwable t) { IJError.print(t); return null; }
	}

	static public final boolean saveToFile(final File f, final String contents) {
		if (null == f) return false;
		try { return new TaskOnEDT<Boolean>(new Callable<Boolean>() { public Boolean call() {
		OutputStreamWriter dos = null;
		try {
			dos = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(f), contents.length()), "8859_1"); // encoding in Latin 1 (for macosx not to mess around
			//dos.writeBytes(contents);
			dos.write(contents, 0, contents.length());
			dos.flush();
			dos.close();
		} catch (Exception e) {
			IJError.print(e);
			Utils.showMessage("ERROR: Most likely did NOT save your file.");
			try {
				if (null != dos) dos.close();
			} catch (Exception ee) { IJError.print(ee); }
			return false;
		}
		return true;
		}}).get(); } catch (Throwable t) { IJError.print(t); return false; }
	}

	static public final boolean saveToFile(String name, String extension, String contents) {
		if (null == contents) {
			Utils.log2("Utils.saveToFile: contents is null");
			return false;
		}
		// save the file
		File f = Utils.chooseFile(name, extension);
		return Utils.saveToFile(f, contents);
	}

	/** Converts sequences of spaces into single space, and trims the ends. */
	static public final String cleanString(String s) {
		s = s.trim();
		while (-1 != s.indexOf("\u0020\u0020")) { // \u0020 equals a single space
			s = s.replaceAll("\u0020\u0020", "\u0020");
		}
		return s;
	}

	static public final String openTextFile(final String path) {
		if (null == path || !new File(path).exists()) return null;
		final StringBuilder sb = new StringBuilder();
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
		final ArrayList<String> al = new ArrayList<String>();
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

	static public final boolean wrongImageJVersion() {
		boolean b = IJ.versionLessThan("1.37g");
		if (b) Utils.showMessage("TrakEM2 requires ImageJ 1.37g or above.");
		return b;
	}

	static public final boolean java3d = isJava3DInstalled();

	static private final boolean isJava3DInstalled() {
		try {
			Class.forName("javax.vecmath.Point3f");
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
		final ArrayList<String> al_layer_titles =  new ArrayList<String>();
		int i = 0;
		for (final Layer layer : first.getParent().getLayers()) {
			layers[i] = first.getProject().findLayerThing(layer).toString();
			al_layer_titles.add(layers[i]);
			i++;
		}
		final int i_first = first.getParent().indexOf(first);
		final int i_last = last.getParent().indexOf(last);
		gd.addChoice("Start: ", layers, layers[i_first]);
		final Vector<?> v = gd.getChoices();
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
		final ArrayList<String> al_layer_titles =  new ArrayList<String>();
		int i = 0;
		for (final Layer layer : selected.getParent().getLayers()) {
			layers[i] = selected.getProject().findLayerThing(layer).toString();
			al_layer_titles.add(layers[i]);
			i++;
		}
		final int i_layer = selected.getParent().indexOf(selected);
		gd.addChoice(label, layers, layers[i_layer]);

	}

	static public final void addRGBColorSliders(final GenericDialog gd, final Color color) {
		gd.addSlider("Red: ", 0, 255, color.getRed());
		gd.addSlider("Green: ", 0, 255, color.getGreen());
		gd.addSlider("Blue: ", 0, 255, color.getBlue());
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

	static public final long[] copy(final long[] a, final int new_length) {
		final long[] b = new long[new_length];
		final int len = a.length > new_length ? new_length : a.length; 
		System.arraycopy(a, 0, b, 0, len);
		return b;
	}

	static public final double[] copy(final double[] a, final int first, final int new_length) {
		final double[] b = new double[new_length];
		final int len = new_length < a.length - first ? new_length : a.length - first;
		System.arraycopy(a, first, b, 0, len);
		return b;
	}

	static public final long[] copy(final long[] a, final int first, final int new_length) {
		final long[] b = new long[new_length];
		final int len = new_length < a.length - first ? new_length : a.length - first;
		System.arraycopy(a, first, b, 0, len);
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
	/** Reverse in place an array of longs. */
	static public final void reverse(final long[] a) {
		for (int left=0, right=a.length-1; left<right; left++, right--) {
			long tmp = a[left];
			a[left] = a[right];
			a[right] = tmp;
		}
	}

	static public final double[] toDouble(final int[] a, final int len) {
		final double[] b = new double[len];
		for (int i=0; i<len; i++) b[i] = a[i];
		return b;
	}

	static public final int[] toInt(final double[] a, final int len) {
		final int[] b = new int[len];
		for (int i=0; i<len; i++) b[i] = (int) a[i];
		return b;
	}

	/** OS-agnostic diagnosis of whether the click was for the contextual popup menu. */
	static public final boolean isPopupTrigger(final MouseEvent me) {
		// ImageJ way, in ij.gui.ImageCanvas class, plus an is-windows switch to prevent meta key from poping up for MacOSX
		return (me.isPopupTrigger() && me.getButton() != 0)  || (IJ.isWindows() && 0 != (me.getModifiers() & Event.META_MASK) );
	}

	/** Repaint the given Component on the swing repaint thread (aka "SwingUtilities.invokeLater"). */
	static public final void updateComponent(final Component c) {
		//c.invalidate();
		//c.validate();
		// ALL that was needed: to put it into the swing repaint queue ... couldn't they JUST SAY SO
		Utils.invokeLater(new Runnable() {
			public void run() {
				c.repaint();
			}
		});
	}
	/** Like calling pack() on a Frame but on a Component. */
	static public final void revalidateComponent(final Component c) {
		Utils.invokeLater(new Runnable() {
			public void run() {
				c.invalidate();
				c.validate();
				c.repaint();
			}
		});
	}

	/** Returns the time as HH:MM:SS */
	static public final String now() {
		/* Java time management is retarded. */
		final Calendar c = Calendar.getInstance();
		final int hour = c.get(Calendar.HOUR_OF_DAY);
		final int min = c.get(Calendar.MINUTE);
		final int sec = c.get(Calendar.SECOND);
		final StringBuilder sb = new StringBuilder();
		if (hour < 10) sb.append('0');
		sb.append(hour).append(':');
		if (min < 10) sb.append('0');
		sb.append(min).append(':');
		if (sec < 10) sb.append(0);
		return sb.append(sec).toString();
	}

	static public final void sleep(final long miliseconds) {
		try { Thread.sleep(miliseconds); } catch (Exception e) { e.printStackTrace(); }
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

	/** 1 A, 2 B, 3 C  ---  26 - z, 27 AA, 28 AB, 29 AC  --- 26*27 AAA */
	static public final String getCharacter(int i) {
		i--;
		int k = i / 26;
		char c = (char)((i % 26) + 65); // 65 is 'A'
		if (0 == k) return Character.toString(c);
		return new StringBuilder().append(getCharacter(k)).append(c).toString();
	}

	static public final Object getField(final Object ob, final String field_name) {
		if (null == ob || null == field_name) return null;
		return getField(ob, ob.getClass(), field_name);
	}

	/** Get by reflection a private or protected field in the given object. */
	static public final Object getField(final Object ob, final Class<?> c, final String field_name) {
		try {
			Field f = c.getDeclaredField(field_name);
			f.setAccessible(true);
			return f.get(ob);
		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
	}
	static public final void setField(final Object ob, final Class<?> c, final String field_name, final Object value) {
		try {
			Field f = c.getDeclaredField(field_name);
			f.setAccessible(true);
			f.set(ob, value);
		} catch (Exception e) {
			IJError.print(e);
		}
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
		try { return new TaskOnEDT<ResultsTable>(new Callable<ResultsTable>() { public ResultsTable call() {
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
		}}).get(); } catch (Throwable t) { IJError.print(t); return null; }
	}

	static public final ImageProcessor createProcessor(final int type, final int width, final int height) {
		switch (type) {
			case ImagePlus.GRAY8: return new ByteProcessor(width, height);
			case ImagePlus.GRAY16: return new ShortProcessor(width, height);
			case ImagePlus.GRAY32: return new FloatProcessor(width, height);
			case ImagePlus.COLOR_RGB: return new ColorProcessor(width, height);
		}
		return null;
	}

	/** Paints an approximation of the pipe into the set of slices. */
	static public void paint(final Pipe pipe, final Map<Layer,ImageProcessor> slices, final int value, final float scale) {
		VectorString3D vs = pipe.asVectorString3D();
		vs.resample(1); // one pixel
		double[] px = vs.getPoints(0);
		double[] py = vs.getPoints(1);
		double[] pz = vs.getPoints(2);
		double[] pr = vs.getDependent(0);
		// For each point
		for (int i=0; i<px.length-1; i++) {
			ImageProcessor ip = slices.get(pipe.getLayerSet().getNearestLayer(pz[i]));
			if (null == ip) continue;
			OvalRoi ov = new OvalRoi((int)((px[i] - pr[i]) * scale),
					         (int)((py[i] - pr[i]) * scale),
						 (int)(pr[i]*2*scale), (int)(pr[i]*2*scale));
			ip.setRoi(ov);
			ip.setValue(value);
			ip.fill(ip.getMask());
		}
	}

	static final public boolean matches(final String pattern, final String s) {
		return Pattern.compile(pattern).matcher(s).matches();
	}

	static final public boolean isValidIdentifier(final String s) {
		if (null == s) return false;
		if (!Utils.matches("^[a-zA-Z]+[a-zA-Z1-9_]*$", s)) {
			Utils.log("Invalid identifier " + s);
			return false;
		}
		return true;
	}

	/**
	user=> (def pat #"\b[a-zA-Z]+[\w]*\b")
	#'user/pat
	user=>(re-seq pat "abc def 1a334")
	("abc" "def")
	user=> (re-seq pat "abc def a334")
	("abc" "def" "a334")

	Then concatenate all good words with underscores.
	Returns null when nothing valid is found in 's'.
	*/
	static final public String makeValidIdentifier(final String s) {
		if (null == s) return null;
		// Concatenate all good groups with underscores:
		final Pattern pat = Pattern.compile("\\b[a-zA-Z]+[\\w]*\\b");
		final Matcher m = pat.matcher(s);
		final StringBuilder sb = new StringBuilder();
		while (m.find()) {
			sb.append(m.group()).append('_');
		}
		if (0 == sb.length()) return null;
		// Remove last underscore
		sb.setLength(sb.length()-1);
		return sb.toString();
	}

	static final public int indexOf(final Object needle, final Object[] haystack) {
		for (int i=0; i<haystack.length; i++) {
			if (haystack[i].equals(needle)) return i;
		}
		return -1;
	}

	/** Remove the file, or if it's a directory, recursively go down subdirs and remove all contents, but will stop on encountering a non-hidden file that is not an empty dir. */
	static public final boolean removeFile(final File f) {
		return Utils.removeFile(f, true, null);
	}

	// Accumulates removed files (not directories) into removed_paths, if not null.
	static private final boolean removeFile(final File f, final boolean stop_if_dir_not_empty, final ArrayList<String> removed_paths) {
		if (null == f || !f.exists()) return false;
		try {
			if (!Utils.isTrakEM2Subfile(f)) {
				Utils.log2("REFUSING to remove file " + f + "\n-->REASON: not in a '/trakem2.' file path");
				return false;
			}

			// If it's not a directory, just delete it
			if (!f.isDirectory()) {
				return f.delete();
			}
			// Else delete all directories:
			final ArrayList<File> dirs = new ArrayList<File>();
			dirs.add(f);
			// Non-recursive version ... I hate java
			do {
				int i = dirs.size() -1;
				final File fdir = dirs.get(i);
				Utils.log2("Examining folder for deletion: " + fdir.getName());
				boolean remove = true;
				File[] files = fdir.listFiles();
				if (null != files) { // can be null if the directory doesn't contain any files. Why not just return an empty array!?
					for (final File file : files) {
						String name = file.getName();
						if (name.equals(".") || name.equals("..")) continue;
						if (file.isDirectory()) {
							remove = false;
							dirs.add(file);
						} else if (file.isHidden()) {
							if (!file.delete()) {
								Utils.log("Failed to delete hidden file " + file.getAbsolutePath());
								return false;
							}
							if (null != removed_paths) removed_paths.add(file.getAbsolutePath());
						} else if (stop_if_dir_not_empty) {
							//Utils.log("Not empty: cannot remove dir " + fdir.getAbsolutePath());
							return false;
						} else {
							if (!file.delete()) {
								Utils.log("Failed to delete visible file " + file.getAbsolutePath());
								return false;
							}
							if (null != removed_paths) removed_paths.add(file.getAbsolutePath());
						}
					}
				}
				if (remove) {
					dirs.remove(i);
					if (!fdir.delete()) {
						return false;
					} else {
						Utils.log2("Removed folder " + fdir.getAbsolutePath());
					}
				}
			} while (dirs.size() > 0);
			
			return true;

		} catch (Exception e) {
			IJError.print(e);
		}
		return false;
	}

	/** Returns true if the file cannonical path contains "/trakem2." (adjusted for Windows as well). */
	static public boolean isTrakEM2Subfile(final File f) throws Exception {
		return isTrakEM2Subpath(f.getCanonicalPath());
	}

	/** Returns true if the path contains "/trakem2." (adjusted for Windows as well). */
	static public boolean isTrakEM2Subpath(String path) {
		if (IJ.isWindows()) path = path.replace('\\', '/');
		return -1 != path.toLowerCase().indexOf("/trakem2.");
	}

	/** Returns true if all files and their subdirectories, recursively, under parent folder have been removed.
	 *  For safety reasons, this function will return false immediately if the parent file path does not include a
	 *  lowercase "trakem2." in it.
	 *  If removed_paths is not null, all removed full paths are added to it.
	 *  Returns false if some files could not be removed.
	 */
	static public final boolean removePrefixedFiles(final File parent, final String prefix, final ArrayList<String> removed_paths) {
		if (null == parent || !parent.isDirectory()) return false;

		try {
			if (!Utils.isTrakEM2Subfile(parent)) {
				Utils.log2("REFUSING to remove files recursively under folder " + parent + "\n-->REASON: not in a '/trakem2.' file path");
				return false;
			}

			final File[] list = parent.listFiles(new FilenameFilter() {
				public boolean accept(File dir, String name) {
					if (name.startsWith(prefix)) return true;
					return false;
				}
			});

			boolean success = true;
			ArrayList<String> a = null;
			if (null != removed_paths) a = new ArrayList<String>();

			if (null != list && list.length > 0) {
				for (final File f : list) {
					if (!Utils.removeFile(f, false, a)) success = false;
					if (null != removed_paths) {
						removed_paths.addAll(a);
						a.clear();
					}
				}
			}

			return success;

		} catch (Exception e) {
			IJError.print(e);
		}
		return false;
	}

	/** The CTRL key functionality is passed over to the COMMAND key (aka META key) in a MacOSX. */
	static public final int getControlModifier() {
		return IJ.isMacOSX() ? InputEvent.META_MASK
			             : InputEvent.CTRL_MASK;
	}

	/** The CTRL key functionality is passed over to the COMMAND key (aka META key) in a MacOSX. */
	static public final boolean isControlDown(final InputEvent e) {
		return IJ.isMacOSX() ? e.isMetaDown()
			             : e.isControlDown();
	}

	static public final void drawPoint(final java.awt.Graphics g, final int x, final int y) {
		g.setColor(Color.white);
		g.drawLine(x-4, y+2, x+8, y+2);
		g.drawLine(x+2, y-4, x+2, y+8);
		g.setColor(Color.yellow);
		g.fillRect(x+1,y+1,3,3);
		g.setColor(Color.black);
		g.drawRect(x, y, 4, 4);
	}

	static public final String trim(CharSequence sb) {
		char c;
		int start = 0;
		do {
			c = sb.charAt(start);
			start++;
		} while ('\t' == c || ' ' == c || '\n' == c);
		int end = sb.length() -1;
		do {
			c = sb.charAt(end);
			end--;
		} while ('\n' == c || ' ' == c || '\t' == c);

		return sb.subSequence(start-1, end+2).toString();
	}

	static public final void wait(final Collection<Future<?>> fus) {
		for (final Future<?> fu : fus) {
			if (null != fu) try {
				fu.get(); // wait until done
			} catch (Exception e) {
				IJError.print(e);
				if (Thread.currentThread().isInterrupted()) return;
			}
		}
	}

	static public final void waitIfAlive(final Collection<Future<?>> fus, final boolean throwException) {
		for (final Future<?> fu : fus) {
			if (Thread.currentThread().isInterrupted()) return;
			if (null != fu) try {
				fu.get(); // wait until done
			} catch (Exception e) {
				if (throwException) IJError.print(e);
			}
		}
	}

	/** Convert a D:\\this\that\there to D://this/that/there/
	 *  Notice it adds an ending backslash. */
	static public final String fixDir(String path) {
		if (IJ.isWindows()) path = path.replace('\\', '/');
		return '/' == path.charAt(path.length() -1) ?
			  path
			: new StringBuilder(path.length() +1).append(path).append('/').toString();
	}

	/** Creates a new fixed thread pool whose threads are in the same ThreadGroup as the Thread that calls this method.
	 *  This allows for the threads to be interrupted when the caller thread's group is interrupted. */
	static public final ThreadPoolExecutor newFixedThreadPool(final int n_proc) {
		return newFixedThreadPool(n_proc, null);
	}
	/** Creates a new fixed thread pool with as many threads as CPUs, and whose threads are in the same ThreadGroup as the Thread that calls this method. */
	static public final ThreadPoolExecutor newFixedThreadPool(final String namePrefix) {
		return newFixedThreadPool(Runtime.getRuntime().availableProcessors(), namePrefix);
	}
	static public final ThreadPoolExecutor newFixedThreadPool(final int n_proc, final String namePrefix) {
		final ThreadPoolExecutor exec = (ThreadPoolExecutor) Executors.newFixedThreadPool(n_proc);
		final AtomicInteger ai = new AtomicInteger(0);
		exec.setThreadFactory(new ThreadFactory() {
			public Thread newThread(final Runnable r) {
				final ThreadGroup tg = Thread.currentThread().getThreadGroup();
				final Thread t = new CachingThread(tg, r, new StringBuilder(null == namePrefix ? tg.getName() : namePrefix).append('-').append(ai.incrementAndGet()).toString());
				t.setDaemon(true);
				t.setPriority(Thread.NORM_PRIORITY);
				return t;
			}
		});
		return exec;
	}
	/** If both are null will throw an error. */
	static public final boolean equalContent(final Collection<?> a, final Collection<?> b) {
		if ((null == a && null != b)
		 || (null != b && null == b)) return false;
		if (a.size() != b.size()) return false;
		for (Iterator<?> ia = a.iterator(), ib = b.iterator(); ia.hasNext(); ) {
			if (!ia.next().equals(ib.next())) return false;
		}
		return true;
	}
	/** If both are null will throw an error. */
	static public final boolean equalContent(final Map<?,?> a, final Map<?,?> b) {
		if ((null == a && null != b)
		 || (null != b && null == b)) return false;
		if (a.size() != b.size()) return false;
		for (final Map.Entry<?,?> e : a.entrySet()) {
			final Object ob = b.get(e.getKey());
			if (null != ob && !ob.equals(e.getValue())) return false;
			if (null != e.getValue() && !e.getValue().equals(ob)) return false;
			// if both are null that's ok
		}
		return true;
	}

	/** Returns false if none to add. */
	static public final boolean addPlugIns(final JPopupMenu popup, final String menu, final Project project, final Callable<Displayable> active) {
		JMenu m = addPlugIns(menu, project, active);
		if (null == m) return false;
		popup.add(m);
		return true;
	}

	/** Returns null if none to add. */
	static public final JMenu addPlugIns(final String menu, final Project project, final Callable<Displayable> active) {
		final Map<String,TPlugIn> plugins = project.getPlugins(menu);
		if (0 == plugins.size()) return null;
		Displayable d = null;
		try {
			d = active.call();
		} catch (Exception e) {
			IJError.print(e);
		}
		final JMenu m = new JMenu("Plugins");
		JMenuItem item;
		int count = 0;
		for (final Map.Entry<String,TPlugIn> e : plugins.entrySet()) {
			item = new JMenuItem(e.getKey());
			item.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent ae) {
					Bureaucrat.createAndStart(new Worker.Task(e.getKey()) {
						public void exec() {
							try {
								e.getValue().invoke(active.call());
							} catch (Exception e) {
								IJError.print(e);
							}
						}
					}, project);
				}
			});
			item.setEnabled(e.getValue().applies(d));
			if (count < 9) {
				item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_1 + count, Utils.getControlModifier(), true));
			}
			m.add(item);
			count++;
		}
		if (0 == m.getItemCount()) return null;
		m.addSeparator();
		// Now all the "Setup " + name
		for (final Map.Entry<String,TPlugIn> e : plugins.entrySet()) {
			item = new JMenuItem("Setup " + e.getKey());
			item.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent ae) {
					Bureaucrat.createAndStart(new Worker.Task(e.getKey()) {
						public void exec() {
							try {
								e.getValue().setup(active.call());
							} catch (Exception e) {
								IJError.print(e);
							}
						}
					}, project);
				}
			});
			m.add(item);
		}
		return m;
	}

	/** Returns null if no plugin was launched.
	 *  To launch a plugin, it needs a Utils.getControlModifier + 1,2,3,4... up to 9.
	 *  @param active may be null. */
	static final public Bureaucrat launchTPlugIn(final KeyEvent ke, final String menu, final Project project, final Displayable active) {
		try {
			if (0 == (ke.getModifiers() ^ Utils.getControlModifier())) {
				final TreeMap<String,TPlugIn> plugins = project.getPlugins("Display");
				if (plugins.size() > 0) {
					int index = ke.getKeyCode() - KeyEvent.VK_1;
					if (index < plugins.size()) {
						int count = 0;
						for (final Map.Entry<String,TPlugIn> e : plugins.entrySet()) {
							if (index != count) {
								count++;
								continue;
							}
							return Bureaucrat.createAndStart(new Worker.Task(e.getKey()) {
								public void exec() {
									e.getValue().invoke(active);
								}
							}, project);
						}
					}
				}
			}
		} catch (Throwable t) {
			IJError.print(t);
		}
		return null;
	}

	static private java.awt.Frame frame = null;

	/** Get the width and height of single-line text. */
	static public final Dimension getDimensions(final String text, final Font font) {
		if (null == frame) { frame = new java.awt.Frame(); frame.pack(); frame.setBackground(Color.white); } // emulating the ImageProcessor class
		FontMetrics fm = frame.getFontMetrics(font);
		int[] w = fm.getWidths(); // the advance widths of the first 256 chars
		int width = 0;
		for (int i = text.length() -1; i>-1; i--) {
			int c = (int)text.charAt(i);
			if (c < 256) width += w[c];
		}
		return new Dimension(width, fm.getHeight());
	}

	static public final java.io.InputStream createStream(final String path_or_url) throws Exception {
		return 0 == path_or_url.indexOf("http://") ?
			  new java.net.URL(path_or_url).openStream()
			: new java.io.BufferedInputStream(new java.io.FileInputStream(path_or_url));
	}

	static public final List<Long> asList(final long[] ids) {
		return asList(ids, 0, ids.length);
	}
	static public final List<Long> asList(final long[] ids, final int first, final int length) {
		final ArrayList<Long> l = new ArrayList<Long>();
		if (null == ids) return l;
		for (int i=first; i<length; i++) l.add(ids[i]);
		return l;
	}
	/** Recursively enable/disable all components of the @param root Container. */
	static public void setEnabled(final Container root, final boolean b) {
		final LinkedList<Container> cs = new LinkedList<Container>();
		cs.add(root);
		while (cs.size() > 0) {
			for (final Component c : cs.removeLast().getComponents()) {
				if (c instanceof Container) cs.add((Container)c);
				c.setEnabled(b);
			}
		}
	}

	static public final boolean ensure(final String filepath) {
		return ensure(new File(filepath));
	}
	
	/** Ensure the file can be written to. Will create parent directories if necessary. */
	static public final boolean ensure(final File f) {
		final File parent = f.getParentFile();
		if (!parent.exists()) {
			if (!parent.mkdirs()) {
				Utils.log("FAILED to create directories " + parent.getAbsolutePath());
				return false;
			}
		} else if (!parent.canWrite()) {
			Utils.log("Cannot write to parent directory " + parent.getAbsolutePath());
			return false;
		}
		return true;
	}

	/** 0.3 * R + 0.6 * G + 0.1 * B */
	public static final int luminance(Color c) {
		return (int)(c.getRed() * 0.3f + c.getGreen() * 0.6f + c.getBlue() * 0.1f + 0.5f);
	}

	/** Invoke in the context of the event dispatch thread. */
	public static final void invokeLater(final Runnable r) {
		if (EventQueue.isDispatchThread()) r.run();
		else SwingUtilities.invokeLater(r);
	}

	public static final void showAllTables(HashMap<Class<?>, ResultsTable> ht) {
		for (Map.Entry<Class<?>,ResultsTable> entry : ht.entrySet()) {
			final Class<?> c = entry.getKey();
			String title;
			if (Profile_List.class == c) title = "Profile List";
			else {
				title = c.getName();
				int idot = title.lastIndexOf('.');
				if (-1 != idot) title = title.substring(idot+1);
			}
			entry.getValue().show(title + " results");
		}
	}
	
	/** Returns a byte[3][256] containing the colors of the fire LUT. */
	public static final IndexColorModel fireLUT() {
		ImagePlus imp = new ImagePlus("fire", new ByteProcessor(1, 1));
		IJ.run(imp, "Fire", "");
		return (IndexColorModel) imp.getProcessor().getColorModel();
	}
	

	static public final BufferedImage convertToBufferedImage(final ByteProcessor bp) {
		bp.setMinAndMax(0, 255); // TODO what is this doing here? The ByteProcessor.setMinAndMax is destructive, it expands the pixel values to the desired range.
		final Image img = bp.createImage();
		if (img instanceof BufferedImage) return (BufferedImage)img;
		//else:
		final BufferedImage bi = new BufferedImage(bp.getWidth(), bp.getHeight(), BufferedImage.TYPE_BYTE_INDEXED, Loader.GRAY_LUT);
		bi.createGraphics().drawImage(img, 0, 0, null);
		return bi;
	}

	/**
	 * 
	 * @param source The file to copy.
	 * @param target The new file to create.
	 * @return Whether the file could be copied; also returns false if the target file exists.
	 * @throws IOException 
	 */
	static public final boolean safeCopy(final String source, final String target) throws IOException {
		final File f2 = new File(target);
		if (f2.exists()) return false;
		RandomAccessFile sra = null,
		                 tra = null;
		try {
			Utils.ensure(f2);
			sra = new RandomAccessFile(new File(source), "r");
			tra = new RandomAccessFile(f2, "rw");
			sra.getChannel().transferTo(0, sra.length(), tra.getChannel());
		} finally {
			if (null != tra) tra.close();
			if (null != sra) sra.close();
		}
		return true;
	}

    static public final <A, B> ArrayList<B> castCollection(
            final Collection<A> classAs, final Class<B> type, final boolean doThrow)
    {
        ArrayList<B> classBs = new ArrayList<B>(classAs.size());
        for (final A a : classAs)
        {
            try
            {
                classBs.add((B)a);
            }
            catch (final ClassCastException cce)
            {
                if (doThrow)
                {
                    throw cce;
                }
            }
        }
        return classBs;
    }
}
