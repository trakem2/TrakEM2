package ini.trakem2.display;

import ij.CommandListener;
import ij.Executer;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Toolbar;

import ini.trakem2.Project;
import ini.trakem2.display.*;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.tree.ProjectTree;
import ini.trakem2.tree.ProjectThing;

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

	// I know, I could create a hashtable and then map methods of this class to each command key ... this is just easier, and performance-wise nobody cares
	//  Or even a hastable with String command keys and then a number as value, and use a gigantic switch block. So much pain to write. WHAT I REALLY WANT is a switch that takes a String and is fast because it has its own hash setup.
	public String commandExecuting(final String command) {
		Utils.log2("Command: " + command);
		// 1 - check source
		ImagePlus current = WindowManager.getCurrentImage();
		if (!(current instanceof FakeImagePlus)) return command;  // not a trakem2 display: continue happily
		// 2 - identify project
		FakeImagePlus fimp = (FakeImagePlus)current;
		Display display = fimp.getDisplay();
		Project project = display.getProject();
		ProjectTree ptree = project.getProjectTree();
		Displayable active = display.getActive();
		Selection selection = display.getSelection();
		// 3 - filter accordingly
		//
		// EDIT menu
		if (command.equals("Undo")) {
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
		} else if (command.equals("Select None")) {
			if (ProjectToolbar.SELECT == Toolbar.getToolId()) {
				display.select(null);
				return null;
			}
		} else if (command.equals("Restore Selection")) {
			if (ProjectToolbar.SELECT == Toolbar.getToolId()) {
				selection.restore();
				return null;
			}
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
			// TODO forward to the active image, if any
			niy(command);
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
			// TODO forward to the active image, if any
			niy(command);
			return null;
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
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Plot Z-axis Profile")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Start Animation [\\]")) {
			// TODO forward to the active image, if any
			niy(command);
			return null;
		} else if (command.equals("Stop Animation")) {
			// TODO forward to the active image, if any
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
				project.getLoader().releaseToFit((long)(project.getLoader().estimateImageFileSize((Patch)active, 0) * 2.5)); // 2.5 secutiry factor: for awt in non-1.6.0 machines
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

		// IMAGE ZOOM menu
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


	
		// with LUTs, most likely a redirection can be done at FakeImageProcessor.setColorModel(..) level


		} else {
			// continue happily
			Utils.log2("Skipping " + command);
		}

		// give it back to ImageJ for execution
		return command;
	}
}
