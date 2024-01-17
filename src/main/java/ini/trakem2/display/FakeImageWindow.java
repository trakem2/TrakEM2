/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2024 Albert Cardona, Stephan Saalfeld and others.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */


package ini.trakem2.display;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ini.trakem2.utils.Utils;

import java.awt.Graphics;

/** A class to prevent ROIs from failing. */
public class FakeImageWindow extends ImageWindow {

	private static final long serialVersionUID = 1L;
	private Display display;

	public FakeImageWindow(ImagePlus imp, ImageCanvas ic, Display display) {
		super(imp.getTitle());
		this.display = display;
		ij = IJ.getInstance();
		this.imp = imp;
		this.ic = ic;
		imp.setWindow(this);
		WindowManager.addWindow(this);
	}

	/** Returns the display's FakeImagePlus. */
	public ImagePlus getImagePlus() {
		return super.getImagePlus();
	}

	// just in case .. although it never should be shown
	public void drawInfo(Graphics g) {
		Utils.log("FakeImageWindow: can't drawInfo");
	}

	public void paint(Graphics g) {
		Utils.log("FakeImageWindow: can't paint");
	}

	/* // problematic, ImageJ doesn't quit
	public boolean close() {
		WindowManager.removeWindow(this);
		if (ij.quitting()) return true; // just let go
		if (display.remove(true)) { // check
			display = null;
			return true;
		} else return false;
	}
	*/

	public void updateImage(ImagePlus imp) {
		Utils.log("FakeImageWindow: Can't updateImage");
	}

	public boolean isClosed() {
		return null == display;
	}

	public void paste() {
		Utils.log("FakeImageWindow: can't paste"); // TODO test how
	}
}
