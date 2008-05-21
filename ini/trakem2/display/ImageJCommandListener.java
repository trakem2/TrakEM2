package ini.trakem2.display;

import ij.CommandListener;
import ij.Executer;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Toolbar;
import ij.gui.Roi;

import ini.trakem2.Project;
import ini.trakem2.display.*;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.tree.ProjectTree;
import ini.trakem2.tree.ProjectThing;
import ini.trakem2.persistence.Loader;

import java.awt.Rectangle;

/** Intercept ImageJ menu commands if the current active image is a FakeImagePlus (from a ini.trakem2.display.Display.) */
public class ImageJCommandListener implements CommandListener {

	public ImageJCommandListener() {
		ij.Executer.addCommandListener(this);
	}

	public void destroy() {
		ij.Executer.removeCommandListener(this);
	}

	private void niy(String command) {
		Utils.log("'" + command + "' -- not implemented yet.");
	}

	private void notAvailable(String command) {
		Utils.log("'" + command + "' -- is not available.");
	}

	private boolean in(String command, String[] list) {
		for (int i=0; i<list.length; i++) {
			if (command.equals(list[i])) return true;
		}
		return false;
	}

	private boolean isPatch(final String command, final Displayable active) {
		if (null == active) {
			Utils.log("Nothing selected!");
			return false;
		}
		if (!(active instanceof Patch)) {
			Utils.log("Can't run '" + command + "' on a non-image object.");
			return false;
		}
		return true;
	}

	/** Set as current image the Patch ImagePlus only for half a second, for a plugin to see it and grab a pointer.
	 * YES this is a tremendous hack that may not work in all situations, but will be reasonably ok for PlugInFilter operations.
	 * This action is intended for commands that don't alter the original image.
	 * */
	private String setTempAndReset(final String command, final Displayable active, final FakeImagePlus fimp) {
		if (!isPatch(command, active)) return null;
		final Patch pa = (Patch)active;
		final Project project = pa.getProject();
		Loader.setTempCurrentImage(pa.getImagePlus());
		project.getLoader().releaseToFit((long)(project.getLoader().estimateImageFileSize(pa, 0) * 5));
		new Thread() {
			public void run() {
				try { Thread.sleep(500); } catch (Exception e) {}
				project.getLoader().setTempCurrentImage(fimp);
			}
		}.start();
		return command;
	}

	/** Duplicate the active image (if any) and set it as active, so the command is run on it. */
	private String duplicate(final String command, final Displayable active) {
		if (!isPatch(command, active)) return null;
		Patch pa = (Patch)active;
		Project project = pa.getProject();
		project.getLoader().releaseToFit((long)(project.getLoader().estimateImageFileSize(pa, 0) * 5));
		ImagePlus imp = new ImagePlus("Copy of " + pa.getTitle(), pa.getImageProcessor().duplicate()); // don't show it yet
		Loader.setTempCurrentImage(imp);
		imp.show();
		// now execute command
		return command;
	}

	// I know, I could create a hashtable and then map methods of this class to each command key ... this is just easier, and performance-wise nobody cares
	//  Or even a hastable with String command keys and then a number as value, and use a gigantic switch block. So much pain to write. WHAT I REALLY WANT is a switch that takes a String and is fast because it has its own hash setup.
	public String commandExecuting(final String command) {
		Utils.log2("Command: " + command);
		// 1 - check source
		ImagePlus current = WindowManager.getCurrentImage();
		if (!(current instanceof FakeImagePlus)) return command;  // not a trakem2 display: continue happily
		// 2 - identify project
		final FakeImagePlus fimp = (FakeImagePlus)current;
		final Display display = fimp.getDisplay();
		final Project project = display.getProject();
		final ProjectTree ptree = project.getProjectTree();
		final Displayable active = display.getActive();
		final Selection selection = display.getSelection();
		// 3 - filter accordingly
		//
		// FILE menu
		if (command.equals("Save")) {
			project.getLoader().save(project);
			return null;
		}

		// EDIT menu
		else if (command.equals("Undo")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Cut")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Copy")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Copy to System")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Paste")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Clear")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Clear Outside")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Fill")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Draw")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Invert")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		}

		// EDIT - SELECTION menu
		else if (command.equals("Select All")) {
			if (ProjectToolbar.SELECT == Toolbar.getToolId()) {
				selection.selectAll();
				return null;
			}
			return command;
		} else if (command.equals("Select None")) {
			if (ProjectToolbar.SELECT == Toolbar.getToolId()) {
				display.select(null);
				return null;
			}
			return command;
		} else if (command.equals("Restore Selection")) {
			if (ProjectToolbar.SELECT == Toolbar.getToolId()) {
				selection.restore();
				return null;
			}
			return command;
		}

		// IMAGE - TYPE menu
		else if (command.equals("8-bit")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("16-bit")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("32-bit")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("8-bit Color")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("RGB Color")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("RGB Stack") || command.equals("HSB Stack")) {
			Utils.showMessage("Can't convert to " + command);
			return null;
		}


		// IMAGE - ADJUST menu
		else if (command.equals("Brightness/Contrast...")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Window/Level...")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Color Balance...")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Threshold...")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Size...")) {
			if (null != active) selection.specify();
			return null;
		} else if (command.equals("Canvas Size...")) {
			display.resizeCanvas();
			return null;
		}

		// IMAGE menu
		else if (command.equals("Show Info...")) {
			// TODO perhaps it should show only for images ...
			if (null == active) {
				ptree.showInfo(project.getRootProjectThing());
			} else {
				ProjectThing pt = project.findProjectThing(active);
				if (null != pt) ptree.showInfo(pt);
			}
			return null;
		}

		// IMAGE - COLOR menu
		else if (in(command, new String[]{"RGB Split", "RGB Merge...", "Stack to RGB", "Make Composite"})) {
			notAvailable(command);
			return null;
		} else if (command.equals("Show LUT")) {
			return setTempAndReset(command, active, fimp);
		} else if (command.equals("Edit LUT...")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Average Color")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("RGB to CIELAB")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("RGB to Luminance")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		}

		// IMAGE STACK menu
		else if (in(command, new String[]{"Add Slice", "Delete Slice", "Next Slice [>]", "Previous Slice [<]", "Set Slice", "Images to Stack", "Stack to Images", "Make Montage..."})) {
			notAvailable(command);
			return null;
		} else if (command.equals("Reslice [/]...")) {
			// TODO
			niy(command);
			return null;
		} else if (command.equals("Z Project...")) {
			// TODO
			niy(command);
			return null;
		} else if (command.equals("3D Project...")) {
			// TODO 
			niy(command);
			return null;
		} else if (command.equals("Plot Z-axis Profile")) {
			// TODO
			niy(command);
			return null;
		} else if (command.equals("Start Animation [\\]")) {
			// TODO
			niy(command);
			return null;
		} else if (command.equals("Stop Animation")) {
			// TODO
			niy(command);
			return null;
		}

		// IMAGE menu again
		else if (command.equals("Crop")) {
			notAvailable(command);
			return null;
		} else if (in(command, new String[]{"Translate...", "Scale..."})) {
			if (null != active) selection.specify();
			return null;
		} else if (command.equals("Duplicate...")) {
			if (null != active && active.getClass().equals(Patch.class)) {
				// TODO stacks?
				project.getLoader().releaseToFit((long)(project.getLoader().estimateImageFileSize((Patch)active, 0) * 2.5)); // 2.5 security factor: for awt in non-1.6.0 machines
				new ImagePlus(active.getTitle(), ((Patch)active).getImageProcessor().duplicate()).show();
			}
			return null;
		} else if (command.equals("Rename...")) {
			if (null != active) {
				active.adjustProperties();
				Display.updateSelection();
			}
			return null;
		}

		// IMAGE ROTATE menu
		else if (command.equals("Flip Horizontally")) {
			selection.apply(2, new double[]{-1, 1});
			return null;
		} else if (command.equals("Flip Vertically")) {
			selection.apply(2, new double[]{1, -1});
			return null;
		} else if (command.equals("Rotate 90 Degrees Right")) {
			selection.apply(1, new double[]{90});
			return null;
		} else if (command.equals("Rotate 90 Degrees Left")) {
			selection.apply(1, new double[]{-90});
			return null;
		} else if (command.equals("Arbitrarily...")) {
			if (null != active) selection.specify();
			return null;
		}

		// IMAGE ZOOM menu
		else if (command.equals("To Selection")) {
			Roi roi = fimp.getRoi();
			if (null != roi) {
				Rectangle b = roi.getBounds();
				b.x -= b.width/2;
				b.y -= b.height/2;
				b.width *= 2;
				b.height *= 2;
				display.getCanvas().showCentered(b);
			}
			return null;
		} else if (command.equals("View 100%")) {
			// TODO
			niy(command);
			return null;
		}

		// LUTs handled by FakeImagePlus / FakeProcessor setColorModel


		// ANALYZE menu
		else if (command.equals("Measure")) {
			// TODO
			niy(command);
			return null;
		} else if (in(command, new String[]{"Analyze Particles...", "Histogram", "Plot Profile", "Surface Plot...", "Color Inspector 3D", "3D Surface Plot", "Color Histogram"})) {
			return setTempAndReset(command, active, fimp);
		} else if (command.equals("Label")) {
			notAvailable(command);
			return null;
		}

		// PROCESS menu and submenus
		else if (in(command, new String[]{"FFT", "Fast FFT (2D/3D)"})) {
			return setTempAndReset(command, active, fimp);
		} else if (in(command, new String[]{"Bandpass Filter...", "Custom Filter...", "FD Math...", "Swap Quadrants", "Convolve...", "Gaussian Blur...", "Median...", "Mean...", "Minimum...", "Maximum...", "Unsharp Mask...", "Variance...", "Show Circular Masks...", "Subtract Background..."})) {
			return duplicate(command, active);
		} else if (in(command, new String[]{"Smooth", "Sharpen", "Find Edges", "Enhance Contrast", "Add Noise", "Add Specified Noise...", "Salt and Pepper", "Despeckle", "Remove Outliers...", "North", "Northeast", "East", "Southeast", "South", "Southwest", "West", "Northwest", "Make Binary", "Convert to Mask", "Find Maxima...", "Erode", "Dilate", "Open ", "Close-", "Outline", "Fill Holes", "Skeletonize", "Distance Map", "Ultimate Points", "Watershed", "Add...", "Subtract...", "Multiply...", "Divide...", "AND...", "OR...", "XOR...", "Min...", "Max...", "Gamma...", "Log", "Exp", "Square", "Square Root", "Reciprocal", "NaN Background", "Abs"})) {
			return duplicate(command, active);


		} else {
			// continue happily
			Utils.log2("Skipping " + command);
		}

		// give it back to ImageJ for execution
		return command;
	}
}
