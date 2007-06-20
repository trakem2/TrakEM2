/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005,2006 Albert Cardona and Rodney Douglas.

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


import ij.IJ;
import ij.ImagePlus;
import ij.Menus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.YesNoCancelDialog;
import ij.io.*;

import java.awt.Checkbox;
import java.awt.Color;
import java.awt.Component;
import java.awt.MenuBar;
import java.awt.Menu;
import java.awt.MenuItem;
import java.io.*;
import java.awt.event.ItemListener;
import java.awt.event.ItemEvent;


/** Utils class: stores generic widely used methods. In particular, those for logging text messages (for debugging) and also some math and memory utilities.
 *
 *
 */
public class Utils implements ij.plugin.PlugIn {

	static public String version = "0.3u 2007-06-19";

	static public boolean debug = false;
	static public boolean debug_mouse = false;
	static public boolean debug_sql = false;
	static public boolean debug_event = false;
	static public boolean debug_clip = false; //clip for repainting
	static public boolean debug_thing = false;

	static public void debug(String msg) {
		if (debug) IJ.log(msg);
	}

	static public void debugMouse(String msg) {
		if (debug_mouse) IJ.log(msg);
	}

	/** Intended for the user to see. */
	static public void log(String msg) {
		// print caller if possible
		/* Freezes ImageJ !
		StackTraceElement[] elems = new Exception().getStackTrace();
		if (elems.length >= 3) {
			log( "LOG: " + elems[2].getClassName() + "   " + elems[2].getFileName() + " at line " + elems[2].getLineNumber() + ":" + elems[2].getClassName() + "." + elems[2].getMethodName() + "()\n" + msg);
		}
		*/

		//IJ.log(msg);
		System.out.println(msg);
	}

	/** Intended for developers to see. */
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

	static public double fixAtan2Angle(double angle) {
		//Adjusting so that 0 is 3 o'clock, PI+PI/2 is 12 o'clock, PI is 9 o'clock, and PI/2 is 6 o'clock (why atan2 doesn't output angles this way? I remember I has the same problem for Pipe.java in the A_3D_editing plugin)
		//Using schemata in JavaAngles.ai as reference

		//double a = 0.0;

		/*if (angle < 0) {
			a = Math.PI/2 + Math.PI + angle; //+ because the angle is negative!
		} else {
			if (angle >= Math.PI/2) {
				a = angle - Math.PI/2;
			} else {
				a = Math.PI + Math.PI/2 + angle;
			}
		}
		*/ //reversed orientation, Y increases downwards !!

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
			log("Stack trace too short! No useful info");
		} else {
			/*
			log( "******\nThe object " + called_object.getClass().getName() + "   " + called_object.toString() + "\n    has been called at:\n        " + elems[1].getFileName() + " at line " + elems[1].getLineNumber() + ":" + elems[1].getClassName() + "." + elems[1].getMethodName() + "()\n    by: " + elems[2].getClassName() + "   " + elems[2].getFileName() + " at line " + elems[2].getLineNumber() + ":" + elems[2].getClassName() + "." + elems[2].getMethodName() + "()\n*********************");
			*/
			log( "#### START TRACE ####\nObject " + called_object.getClass().getName() + " called at: " + elems[1].getFileName() + " " + elems[1].getLineNumber() + ": " + elems[1].getMethodName() + "()\n    by: " + elems[2].getClassName() + " " + elems[2].getLineNumber() + ": " + elems[2].getMethodName() + "()");
			log("==== END ====");
		}
	}

	static public void printCaller(Object called_object, int lines) {
		StackTraceElement[] elems = new Exception().getStackTrace();
		if (elems.length < 3) {
			log("Stack trace too short! No useful info");
		} else {
			log( "#### START TRACE ####\nObject " + called_object.getClass().getName() + " called at: " + elems[1].getFileName() + " " + elems[1].getLineNumber() + ": " + elems[1].getMethodName() + "()\n    by: " + elems[2].getClassName() + " " + elems[2].getLineNumber() + ": " + elems[2].getMethodName() + "()");
			for (int i=3; i<lines+2 && i<elems.length; i++) {
				log("\tby: " + elems[i].getClassName() + " " + elems[i].getLineNumber() + ": " + elems[i].getMethodName() + "()");
			}
			log("==== END ====");
		}
	}
	/* // old. excessively verbose version
	static public void printCaller(Object called_object, int lines) {
		StackTraceElement[] elems = new Exception().getStackTrace();
		if (elems.length < 3) {
			log("Stack trace too short! No useful info");
		} else {
			log( "******\nThe object " + called_object.getClass().getName() + "   " + called_object.toString() + "\n    has been called at:\n        " + elems[1].getFileName() + " at line " + elems[1].getLineNumber() + ":" + elems[1].getClassName() + "." + elems[1].getMethodName() + "()\n    by: " + elems[2].getClassName() + "   " + elems[2].getFileName() + " at line " + elems[2].getLineNumber() + ":" + elems[2].getClassName() + "." + elems[2].getMethodName() + "()");
			for (int i=3; i<lines+2 && i<elems.length; i++) {
				log("\n    by: " + elems[i].getClassName() + "   " + elems[i].getFileName() + " at line " + elems[i].getLineNumber() + ":" + elems[i].getClassName() + "." + elems[i].getMethodName() + "()");
			}
			log("********************");
		}
	}
	*/

	static public String caller(Object called) {
		StackTraceElement[] elems = new Exception().getStackTrace();
		if (elems.length < 3) {
			log("Stack trace too short! No useful info");
			return null;
		} else {
			return elems[2].getClassName();
		}
	}

	static public String faultyLine(Exception e) {
		StackTraceElement[] elems = new Exception().getStackTrace();
		if (elems.length > 2) {
			return elems[2].getClassName() + "." + elems[2].getMethodName() + " - " + elems[2].getFileName() + ":" + elems[2].getLineNumber();
		}
		return "[unknown: stack too short]";
	}

	/** Fix ImageJ's MenuBar to disactivate all non-desired commands.*/
	/*
	static public void fixMenuBar() {
		//One could consider deleting the commands from the hashtable as well.

		HashSet disabled_commands = new HashSet();
		disabled_commands.add("16-bit");
		disabled_commands.add("32-bit");
		disabled_commands.add("8-bit Color");
		disabled_commands.add("HSB Stack");
		disabled_commands.add("RGB Stack");
		disabled_commands.add("Size...");
		disabled_commands.add("Canvas Size...");

		MenuBar menu_bar = Menus.getMenuBar();
		int n_menus = menu_bar.getMenuCount();
		for (int i=0; i<n_menus;i++) {
			Menu menu = menu_bar.getMenu(i);
			disableUndesiredCommands(menu, disabled_commands);
		}
	}
	*/

	/*
	static private void disableUndesiredCommands(Menu menu, HashSet disabled_commands) {
		int n_menuitems = menu.getItemCount();
		for (int i=0; i<n_menuitems; i++) {
			MenuItem menu_item = menu.getItem(i);
			String command = menu_item.getActionCommand();
			if (menu_item instanceof Menu) {
				disableUndesiredCommands((Menu)menu_item, disabled_commands);
			}
			if (disabled_commands.contains(command)) {
				menu_item.setEnabled(false);
			}
		}
	}
	*/

	/**Restore ImageJ's MenuBar*/
	static public void restoreMenuBar() {
		MenuBar menu_bar = Menus.getMenuBar();
		int n_menus = menu_bar.getMenuCount();
		for (int i=0; i<n_menus;i++) {
			Menu menu = menu_bar.getMenu(i);
			restoreMenu(menu);
		}
		//make sure there isn't a null menu bar
		//WindowManager.getCurrentWindow().setMenuBar(menu_bar);
	}

	static private void restoreMenu(Menu menu) {
		int n_menuitems = menu.getItemCount();
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

	static public void showStatus(String msg, boolean focus) {
		// blocks input tremendously!
		if (null == IJ.getInstance() || !ControlWindow.isGUIEnabled()) {
			System.out.println(msg);
			return;
		}
		if (focus) IJ.getInstance().toFront();
		IJ.showStatus(msg);
		// temporarily:
		//log(msg);
	}

	static public void showStatus(String msg) {
		if (null == IJ.getInstance() || !ControlWindow.isGUIEnabled()) {
			System.out.println(msg);
			return;
		}
		IJ.getInstance().toFront();
		IJ.showStatus(msg);
	}

	static private double last_progress = 0;
	static private int last_percent = 0;

	static public void showProgress(double p) {
		//IJ.showProgress(p); // never happens, can't repaint even though they are different threads
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
	}

	/**Tells whether enough memory is free; used to know whether more stuff can be loaded or some has to be released first. The min_free is the minimum percentage that should be free, for example for 25% it would be 25. */
	/*
	static public boolean enoughFreeMemory(long min_free) {
		// calculate memory in use as percentage of max.
		long mem_in_use = (IJ.currentMemory() * 100) / IJ.maxMemory();
		if (Utils.debug_sql) log("Memory in use: " + mem_in_use + " %");
		if (mem_in_use > (100L - min_free)) {
			return false;
		} else {
			return true;
		}
	}
	*/

	/** Tells whether the given 'size' for an image is openable in the currently available memory minus 3%. */
	/*
	static public boolean canFitInFreeMemory(long size) {
		long max_memory = IJ.maxMemory() - 3L;
		long mem_in_use = (IJ.currentMemory() * 100) / max_memory;
		if (size < max_memory - mem_in_use) {
			return true;
		} else {
			return false;
		}
	}
	*/

	static public void debugDialog() {
		// note: all this could nicely be done using reflection, so adding another boolean variable would be atuomatically added here (filtering by the prefix "debug").
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
	static public String cutNumber(double d, int n_decimals, boolean remove_trailing_zeros) {
		String num = new Double(d).toString();
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

	public void run(String arg) {
		IJ.showMessage("TrakEM2", "TrakEM2 " + Utils.version + "\nCopyright Albert Cardona & Rodney Douglas\nInstitute for Neuroinformatics, Univ. Zurich / ETH\nUniversity of California Los Angeles");
	}

	/** Select a file from the file system, for saving purposes. Prompts for overwritting if the file exists, unless the ControlWindow.isGUIEnabled() returns false (i.e. there is no GUI). */
	static public File chooseFile(String name, String extension) {
		// using ImageJ's JFileChooser or internal FileDialog, according to user preferences.
		String user = System.getProperty("user.name");
		String name2 = null;
		if (null != name && null != extension) name2 = name + extension;
		else if (null != name) name2 = name;
		else if (null != extension) name2 = "untitled" + extension;
		SaveDialog sd = new SaveDialog("Save",
						//(user.startsWith("albert") || user.endsWith("cardona")) ? 
						//	"/home/" + user + "/temp" :
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
		OpenDialog od = new OpenDialog("Select image file", OpenDialog.getDefaultDirectory(), null);
		String file = od.getFileName();
		if (null == file || file.toLowerCase().startsWith("null")) return null;
		String dir = od.getDirectory();

		File f = new File(dir + "/" + file); // I'd use File.separator, but in Windows it fails
		if (!f.exists()) {
			Utils.showMessage("File " + dir + "/" + file  + " does not exist.");
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
			new IJError(e);
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

	static public String openTextFile(final String path) {
		if (!new File(path).exists()) return null;
		StringBuffer sb = new StringBuffer();
		try {
			BufferedReader r = new BufferedReader(new FileReader(path));
			while (true) {
				String s = r.readLine();
				if (null == s) break;
				sb.append(s).append('\n');
        		}
			r.close();
		} catch (Exception e) {
			new IJError(e);
			return null;
		}
		return sb.toString();
	}

	/** The cosinus between two vectors (in polar coordinates), by means of the dot product. */
	static public double getCos(final double x1, final double y1, final double x2, final double y2) {
		return (x1 * x2 + y1 * y2) / (Math.sqrt(x1*x1 + y1*y1) * Math.sqrt(x2*x2 + y2*y2));
	}

	static public String removeExtension(final String path) {
		final int i_dot = path.lastIndexOf('.');
		if (-1 == i_dot || i_dot + 4 != path.length()) return path;
		else return path.substring(0, i_dot);
	}

	/** A helper for GenericDialog checkboxes to control other the enabled state of other GUI elements in the same dialog. */
	static public void addEnablerListener(final Checkbox master, final Component[] enable, final Component[] disable) {
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

	static public int getCPUCount() {
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

	static public boolean wrongImageJVersion() {
		boolean b = IJ.versionLessThan("1.37g");
		if (b) Utils.showMessage("TrakEM2 requires ImageJ 1.37g or above.");
		return b;
	}

	static public boolean java3d = isJava3DInstalled();

	static private boolean isJava3DInstalled() {
		try {
			Class p3f = Class.forName("javax.vecmath.Point3f");
		} catch (ClassNotFoundException cnfe) {
			return false;
		}
		return true;
	}
}
