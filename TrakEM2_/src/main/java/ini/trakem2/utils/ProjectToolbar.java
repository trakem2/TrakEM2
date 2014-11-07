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

package ini.trakem2.utils;

import ij.IJ;
import ij.ImageJ;
import ij.gui.Toolbar;
import ij.plugin.MacroInstaller;
import ini.trakem2.display.Display;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;

import javax.swing.SwingUtilities;

public class ProjectToolbar implements MouseListener {

	/**A tool to select and move Displayable objects (black arrow).*/
	public static final int SELECT = Toolbar.SPARE1;
	/**A tool to draw freehand and then autoconvert to Bezier curves.*/
	public static final int PENCIL = Toolbar.SPARE2;
	/**A tool to draw/edit.*/
	public static final int PEN = Toolbar.SPARE3;
	/**A tool to align objects from two different layers.*/
	public static final int BRUSH = Toolbar.SPARE4;

	public static final int WAND = Toolbar.SPARE5;

	static private String startup_macros = null;

	static private ProjectToolbar instance = null;

	private ProjectToolbar() {}

	/** Set macro buttons for TrakEM2 in ImageJ's toolbar */
	static synchronized public void setProjectToolbar() {
		if (null == instance) instance = new ProjectToolbar();
		// check if macros are installed already
		MacroInstaller installer = new MacroInstaller();
		boolean toolbar_present = false;
		try {
			java.awt.event.ActionListener[] al = ij.Menus.getMacrosMenu().getActionListeners();
			MacroInstaller minst = null;
			for (int j=al.length -1; j>-1; j--) {
				if (al[j] instanceof MacroInstaller) {
					minst = (MacroInstaller)al[j];
					break;
				}
			}
			if (null != minst) {
				java.lang.reflect.Field f_macroNames = MacroInstaller.class.getDeclaredField("macroNames");
				f_macroNames.setAccessible(true);
				Object ob = f_macroNames.get(minst);
				if (null != ob) {	
					String[] macroNames = (String[])ob;
					if (null == macroNames) return;
					if (macroNames.length > 3
					 && null != macroNames[0] && 0 == macroNames[0].indexOf("Select and Transform")
					 && null != macroNames[1] && 0 == macroNames[1].indexOf("Freehand")
					 && null != macroNames[2] && 0 == macroNames[2].indexOf("Pen")
					 && null != macroNames[3] && 0 == macroNames[3].indexOf("Align")
					) {
						toolbar_present = true;
					}
				}
			}
		} catch (Exception e) {
			// the above is not thread safe, will fail many times because the Display being show is also trying to set the toolbar
			Utils.log2("Can't check if toolbar is in place.");
			//IJError.print(e);
			// if it fails, toolbar_present still is false and thus will result in the macros being installed again.
		}

		// sort of a constructor: an embedded macro set
		if (!toolbar_present) {
			int tool = Toolbar.getToolId();
			final StringBuilder sb_tools = new StringBuilder();
			sb_tools.append("macro 'Select and Transform Tool-C000L2242L2363L3494L35b5L46c6L4797L48a8L49b9L5a6aL8acaL5b6bL9bdbL5c5cLacdcLbdcd' {\ncall('ini.trakem2.utils.ProjectToolbar.toolChanged', 'SELECT');\n}\n")
				.append("macro 'Freehand Tool-C000Lb0c0La1d1L92e2L83f3L74f4L65e5L56d6L47c7L38b8L29a9L2a2aL4a9aL1b2bL5b8bL1c1cL6c7cL0d1dL5d6dL0e0eL3e5eL0f3f' {\ncall('ini.trakem2.utils.ProjectToolbar.toolChanged', 'PENCIL');\n}\n")
				.append("macro 'Pen Tool-C000L8080L7191L7292L6363L8383La3a3L6464L8484Lb4b4L5555L8585Lb5b5L4646L8686Lc6c6L4747Lc7c7L3838Ld8d8L4949Lc9c9L4a4aLcacaL5b5bLbbbbL5c5cLbcbcL4dcdL5e5eLbebeL5fbf' {\ncall('ini.trakem2.utils.ProjectToolbar.toolChanged', 'PEN');\n}\n")
				.append("macro 'Brush Tool - C037La077Ld098L6859L4a2fL2f4fL3f99L5e9bL9b98L6888L5e8dL888c' {\ncall('ini.trakem2.utils.ProjectToolbar.toolChanged', 'BRUSH');\n}\n")
			;

			installer.install(sb_tools.toString()); // another call to install erases the previous, so it needs all at the same time
			Toolbar.getInstance().setTool(tool);
		}
	}

	/** Called by macro tools. */ // TODO it's never called but I expect it to; no clue
	static public void toolChanged(String tool_name) {
		//Display.toolChanged(tool_name);
	}

	/** Restore ImageJ's toolbar. */
	static public void setImageJToolbar() {
		// remove mouse listener
		MouseListener[] ml = Toolbar.getInstance().getMouseListeners();
		for (int i=0; i<ml.length; i++) {
			if (ml[i].equals(instance)) {
				Toolbar.getInstance().removeMouseListener(instance);
				break;
			}
		}
		// try to fetch the macros folder
		// The code below does the same as:
		// 	IJ.run("Install...", "install="+IJ.getDirectory("macros")+"StartupMacros.txt");
		// but checking whether the startupmacros.txt file exists
		if (null == startup_macros) {
			String macros_path = ij.Menus.getMacrosPath();
			if (null != macros_path) {
				File f = new File(macros_path);
				if (f.isDirectory()) {
					String[] mf = f.list();
					for (int i=0; i<mf.length; i++) {
						if (mf[i].toLowerCase().equals("startupmacros.txt")) {
							startup_macros = Utils.openTextFile(macros_path + "/" + mf[i]);
							break;
						}
					}
				}
			}
		}
		// else, try to run
		if (null == startup_macros && ImageJ.VERSION.compareTo("1.38a") >= 0) {
			try {
				/* // works locally, but won't on registered applets or java web start
				Toolbar tb = Toolbar.getInstance();
				Field f_sp = Toolbar.class.getDeclaredField("switchPopup");
				f_sp.setAccessible(true);
				PopupMenu popup = (PopupMenu)f_sp.get(tb);
				MenuItem item = null;
				for (int i=popup.getItemCount() -1; i>-1; i--) {
					item = popup.getItem(i);
					if (item.getLabel().equals("StartupMacros*")) {
						break;
					}
				}
				// simulate click
				ItemEvent event = new ItemEvent((ItemSelectable)item,(int)System.currentTimeMillis(),item,1);
				tb.itemStateChanged(event);
				*/
				// Wayne Rasband's solution:
				MacroInstaller mi = new MacroInstaller();
				String path = "/macros/StartupMacros.txt";
				//mi.installFromIJJar(path); // fails absurdly on IJ < 1.38a despite the 'if' clause
				java.lang.reflect.Method m = MacroInstaller.class.getDeclaredMethod("installFromIJJar", new Class[]{});
				m.invoke(mi, new Object[]{path});
				return;
			} catch (Exception e) {
				//e.printStackTrace();
				if (null != IJ.getInstance() && IJ.getInstance().quitting()) {
					Utils.log("Failed to restore ImageJ toolbar");
				}
			}
		}
		if (null != startup_macros) {
			new MacroInstaller().install(startup_macros);
		}
	}

	static public void destroy() {
		if (null != IJ.getInstance() && !IJ.getInstance().quitting()) {
			setImageJToolbar();
		}
	}

	static public void setTool(final int t) {
		SwingUtilities.invokeLater(new Runnable() { public void run() {
			Toolbar.getInstance().setTool(t);
		}});
		Display.repaintToolbar();
	}

	static public int getToolId() {
		int tool = Toolbar.getToolId();
		if (Toolbar.WAND == tool) return ProjectToolbar.WAND;
		return tool;
	}

	public void mousePressed(MouseEvent me) {
		int ij_tool = Toolbar.getToolId();
		Utils.log2("Tool: " + ij_tool);
	}
	public void mouseReleased(MouseEvent me) {}
	public void mouseClicked(MouseEvent me) {}
	public void mouseEntered(MouseEvent me) {}
	public void mouseExited(MouseEvent me) {}

	/** Hacks on the ij.gui.Toolbar to get the proper value, and defaults to 15 if the value is absurd. */
	static public int getBrushSize() {
		int brushSize = 15;
		try {
			java.lang.reflect.Field f = Toolbar.class.getDeclaredField("brushSize");
			f.setAccessible(true);
			brushSize = ((Integer)f.get(Toolbar.getInstance())).intValue();
			if (brushSize < 1) brushSize = 15;
		} catch (Exception e) {}
		return brushSize;
	}

	/** Change the brush size by the given length increment (in pixel units). A lower limit of 1 pixel is preserved. Returns the value finally accepted for brush size.*/
	static public int setBrushSize(int inc) {
		int brushSize = 15;
		try {
			java.lang.reflect.Field f = Toolbar.class.getDeclaredField("brushSize");
			f.setAccessible(true);
			brushSize = ((Integer)f.get(Toolbar.getInstance())).intValue();
			if (brushSize + inc < 1) brushSize = 1;
			else brushSize += inc;
			f.setInt(Toolbar.getInstance(), brushSize);
		} catch (Exception e) {}
		return brushSize;
	}

	static public void keyPressed(KeyEvent ke) {
		switch (ke.getKeyCode()) {
			case KeyEvent.VK_F1:
				setTool(Toolbar.RECTANGLE);
				break;
			case KeyEvent.VK_F2:
				setTool(Toolbar.POLYGON);
				break;
			case KeyEvent.VK_F3:
				setTool(Toolbar.FREEROI);
				break;
			case KeyEvent.VK_F4:
				setTool(Toolbar.TEXT);
				break;
			case KeyEvent.VK_F5:
				setTool(Toolbar.MAGNIFIER);
				break;
			case KeyEvent.VK_F6:
				setTool(Toolbar.HAND);
				break;
			case KeyEvent.VK_F7:
				break;
			case KeyEvent.VK_F8:
				break;
			case KeyEvent.VK_F9:
				setTool(SELECT);
				break;
			case KeyEvent.VK_F10:
				setTool(PENCIL);
				break;
			case KeyEvent.VK_F11:
				setTool(PEN);
				break;
			case KeyEvent.VK_F12:
				setTool(BRUSH);
				break;
		}
	}

	static public boolean isDataEditTool(final int tool) {
		switch (tool) {
			case PENCIL:
			case BRUSH:
			case PEN:
				return true;
			default:
				return false;
		}
	}
	
	/** The luminance of the foreground color. */
	static public final int getForegroundColorValue() {
		return Utils.luminance(Toolbar.getForegroundColor());
	}
}
