package ini.trakem2.persistence;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.io.OpenDialog;
import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Patch;
import ini.trakem2.display.YesNoDialog;
import ini.trakem2.utils.Dispatcher;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.Collection;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Set;
import java.util.Vector;

import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

/** A class to manage "file not found" problems. */
public class FilePathRepair {

	private final Project project;
	private final PathTableModel data = new PathTableModel();
	private final JTable table = new JTable(data);
	private final JFrame frame;

	private FilePathRepair(final Project project) {
		this.project = project;
		this.frame = ControlWindow.createJFrame("Repair: " + project);
	}

	private final Runnable makeGUI() {
		return new Runnable() { public void run() {
			JScrollPane jsp = new JScrollPane(table);
			jsp.setPreferredSize(new Dimension(500, 500));
			table.addMouseListener(listener);
			JLabel label = new JLabel("Double-click any to repair file path:");
			JLabel label2 = new JLabel("(Any listed with identical parent folder will be fixed as well.)");
			JPanel plabel = new JPanel();
			BoxLayout pbl = new BoxLayout(plabel, BoxLayout.Y_AXIS);
			plabel.setLayout(pbl);
			//plabel.setBorder(new LineBorder(Color.black, 1, true));
			plabel.setMinimumSize(new Dimension(400, 40));
			plabel.add(label);
			plabel.add(label2);
			JPanel all = new JPanel();
			BoxLayout bl = new BoxLayout(all, BoxLayout.Y_AXIS);
			all.setLayout(bl);
			all.add(plabel);
			all.add(jsp);
			frame.getContentPane().add(all);
			frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
			frame.addWindowListener(new WindowAdapter() {
				public void windowClosing(WindowEvent we) {
					synchronized (projects) {
						if (data.vpath.size() > 0 ) {
							Utils.logAll("WARNING: Some images remain associated to inexistent file paths.");
						}
						projects.remove(project);
					}
				}
			});
			frame.pack();
			ij.gui.GUI.center(frame);
			frame.setVisible(true);
		}};
	}

	private static class PathTableModel extends AbstractTableModel {
		final Vector<Patch> vp = new Vector<Patch>();
		final Vector<String> vpath = new Vector<String>();
		final HashSet<Patch> set = new HashSet<Patch>();
		PathTableModel() {}
		public final String getColumnName(final int col) {
			switch (col) {
				case 0: return "Image";
				case 1: return "Nonexistent file path";
				default: return "";
			}
		}
		public final int getRowCount() { return vp.size(); }
		public final int getColumnCount() { return 2; }
		public final Object getValueAt(final int row, final int col) {
			switch (col) {
				case 0: return vp.get(row);
				case 1: return vpath.get(row);
				default: return null;
			}
		}
		public final boolean isCellEditable(final int row, final int col) {
			return false;
		}
		public final void setValueAt(final Object value, final int row, final int col) {} // ignore

		synchronized public final void add(final Patch patch) {
			if (set.contains(patch)) return; // already here
			vp.add(patch);
			vpath.add(patch.getImageFilePath()); // no slice information if it's a stack
			set.add(patch);
		}

		synchronized public final String remove(final int row) {
			set.remove(vp.remove(row));
			return vpath.remove(row);
		}

		synchronized public final String remove(final Patch p) {
			final int i = vp.indexOf(p);
			if (-1 == i) return null;
			set.remove(vp.remove(i));
			return vpath.remove(i);
		}
	}

	// Static part

	static private final Hashtable<Project,FilePathRepair> projects = new Hashtable<Project,FilePathRepair>();

	static public void add(final Patch patch) {
		dispatcher.exec(new Runnable() { public void run() {
			final Project project = patch.getProject();
			FilePathRepair fpr = null;
			synchronized (projects) {
				fpr = projects.get(project);
				if (null == fpr) {
					fpr = new FilePathRepair(project);
					projects.put(project, fpr);
					SwingUtilities.invokeLater(fpr.makeGUI());
				}
				fpr.data.add(patch);
				if (!fpr.frame.isVisible()) {
					fpr.frame.setVisible(true);
				} else {
					SwingUtilities.invokeLater(new Repainter(fpr));
				}
			}
		}});
	}

	static private class Repainter implements Runnable {
		FilePathRepair fpr;
		Repainter(final FilePathRepair fpr) { this.fpr = fpr; }
		public void run() {
			try {
				fpr.table.updateUI();
				fpr.table.repaint();
				fpr.frame.pack();
			} catch (Exception e) { IJError.print(e); }
		}
	}

	static private final Dispatcher dispatcher = new Dispatcher("File path fixer");

	static private MouseAdapter listener = new MouseAdapter() {
		public void mousePressed(final MouseEvent me) {
			final JTable table = (JTable) me.getSource();
			final PathTableModel data = (PathTableModel) table.getModel();
			final int row = table.rowAtPoint(me.getPoint());
			if (-1 == row) return;

			if (2 == me.getClickCount()) {
				dispatcher.exec(new Runnable() { public void run() {
					try {
						table.setEnabled(false);
						GenericDialog gd = new GenericDialog("Fix paths");
						gd.addCheckbox("Fix other listed image files with identical parent directory", true);
						gd.addCheckbox("Fix all image files in the project with identical parent directory", true);
						gd.addCheckbox("Update mipmaps for each fixed path", false);
						gd.showDialog();
						if (!gd.wasCanceled()) {
							fixPath(table, data, row, gd.getNextBoolean(), gd.getNextBoolean(), gd.getNextBoolean());
						}
					} catch (Exception e) {
						IJError.print(e);
					} finally {
						table.setEnabled(true);
					}
				}});
			}
		}
	};

	static private void fixPath(final JTable table, final PathTableModel data, final int row, final boolean fix_similar, final boolean fix_all, final boolean update_mipmaps) throws Exception {
		synchronized (projects) {
			final Patch patch = data.vp.get(row);
			if (null == patch) return;
			final String old_path = patch.getImageFilePath();
			final File f = new File(old_path);
			if (f.exists()) {
				Utils.log("File exists for " + patch + " at " + f.getAbsolutePath() + "\n  --> not updating path.");
				data.remove(row);
				return;
			}
			// Else, pop up file dialog
			OpenDialog od = new OpenDialog("Select image file", OpenDialog.getDefaultDirectory(), null);
			String dir = od.getDirectory();
			final String filename = od.getFileName();
			if (null == dir) return; // dialog was canceled
			if (IJ.isWindows()) dir = dir.replace('\\', '/');
			if (!dir.endsWith("/")) dir += "/";
			// Compare filenames
			if ( ! filename.equals(f.getName())) {
				YesNoDialog yn = new YesNoDialog(projects.get(patch.getProject()).frame, "WARNING", "Different file names!\n  old: " + f.getName() + "\n  new: " + filename + "\nSet to new file name?");
				if ( ! yn.yesPressed()) return;

				// Remove mipmaps: would not be found with the new name and the old ones would remain behind unused
				if ( ! f.getName().equals(new File(old_path).getName())) {
					// remove mipmaps: the name wouldn't match otherwise
					patch.getProject().getLoader().removeMipMaps(patch);
				}
			}
			//
			String wrong_parent_path = new File(data.vpath.get(row)).getParent();
			wrong_parent_path = wrong_parent_path.replace('\\', '/');
			if (!wrong_parent_path.endsWith("/")) wrong_parent_path = new StringBuilder(wrong_parent_path).append('/').toString(); // not File.separatorChar, TrakEM2 uses '/' as folder separator

			final String path = new StringBuilder(dir).append(filename).toString();

			// keep track of fixed slices to avoid calling n_slices * n_slices times!
			final HashSet<Patch> fixed = new HashSet<Patch>();

			int n_fixed = 0;

			if (-1 == patch.getFilePath().lastIndexOf("-----#slice=")) {
				if (!fixPatchPath(patch, path, update_mipmaps)) {
					return;
				}
				data.remove(patch);
				fixed.add(patch);
				n_fixed += 1;
			} else {
				int n = fixStack(data, fixed, patch.getStackPatches(), old_path, path, update_mipmaps);
				if (0 == n) {
					return; // some error ocurred, no paths fixed
				}
				n_fixed += n;
				// data already cleared of removed patches by fixStack
			}

			String good_parent_path = dir;
			if (!dir.endsWith("/")) good_parent_path = new StringBuilder(good_parent_path).append('/').toString(); // not File.separatorChar, TrakEM2 uses '/' as folder separator

			// Check for similar parent paths and see if they can be fixed
			if (fix_similar) {
				for (int i=data.vp.size() -1; i>-1; i--) {
					final String wrong_path = data.vpath.get(i);
					final Patch p = data.vp.get(i);
					if (wrong_path.startsWith(wrong_parent_path)) {
						// try to fix as well
						final File file = new File(new StringBuilder(good_parent_path).append(wrong_path.substring(wrong_parent_path.length())).toString());
						if (file.exists()) {
							if (-1 == p.getFilePath().lastIndexOf("-----#slice=")) {
								if (!fixed.contains(p) && fixPatchPath(p, file.getAbsolutePath(), update_mipmaps)) {
									data.remove(p); // not by 'i' but by Patch, since if some fail the order is not the same
									n_fixed++;
									fixed.add(p);
								}
							} else {
								if (fixed.contains(p)) continue;
								n_fixed += fixStack(data, fixed, p.getStackPatches(), wrong_path, file.getAbsolutePath(), update_mipmaps);
							}
						}
					}
				}
			}
			if (fix_all) {
				// traverse all Patch from the entire project, minus those already fixed
				for (final Displayable d : patch.getLayerSet().getDisplayables(Patch.class)) {
					final Patch p = (Patch) d;
					final String wrong_path = p.getImageFilePath();
					if (wrong_path.startsWith(wrong_parent_path)) {
						File file = new File(new StringBuilder(good_parent_path).append(wrong_path.substring(wrong_parent_path.length())).toString());
						if (file.exists()) {
							if (-1 == p.getFilePath().lastIndexOf("-----#slice=")) {
								if (!fixed.contains(p) && fixPatchPath(p, file.getAbsolutePath(), update_mipmaps)) {
									data.remove(p); // not by 'i' but by Patch, since if some fail the order is not the same
									n_fixed++;
									fixed.add(p);
								}
							} else {
								if (fixed.contains(p)) continue;
								n_fixed += fixStack(data, fixed, p.getStackPatches(), wrong_path, file.getAbsolutePath(), update_mipmaps);
							}
						}
					}
				}
			}

			// if table is empty, close
			if (0 == data.vp.size()) {
				FilePathRepair fpr = projects.remove(patch.getProject());
				fpr.frame.dispose();
			}

			Utils.logAll("Fixed " + n_fixed + " image file path" + (n_fixed > 1 ? "s" : ""));
		}
	}

	static private int fixStack(final PathTableModel data, final Set<Patch> fixed, final Collection<Patch> slices, final String wrong_path, final String new_path, final boolean update_mipmaps) {
		int n_fixed = 0;
		Dimension dim = null;
		Loader loader = null;
		for (final Patch ps : slices) {
			if (fixed.contains(ps)) continue;
			final String slicepath = ps.getFilePath();
			final int isl = slicepath.lastIndexOf("-----#slice=");
			if (-1 == isl) {
				Utils.log2("Not a stack path: " + slicepath);
				continue; // someone linked an image...
			}
			final String ps_path = slicepath.substring(0, isl); // same: // ps.getImageFilePath();
			if (! ps_path.substring(0, isl).equals(wrong_path)) {
				Utils.log2("Not the same stack path:\n  i=" + ps_path + "\n  ref=" + wrong_path);
				continue; // not the same stack!
			}
			if (null == dim) {
				loader = ps.getProject().getLoader();
				loader.releaseToFit(Math.max(Loader.MIN_FREE_BYTES, ps.getOWidth() * ps.getOHeight() * 10));
				dim = loader.getDimensions(new_path);
				if (null == dim) {
					Utils.log(new StringBuilder("ERROR: could not open image at ").append(new_path).toString()); // preserving backslashes
					return n_fixed;
				}
				// Check dimensions
				if (dim.width != ps.getOWidth() || dim.height != ps.getOHeight()) {
					Utils.log("ERROR different o_width,o_height for patch " + ps + "\n  at new path " + new_path +
						"\nold o_width,o_height: " + ps.getOWidth() + "," + ps.getOHeight() +
						"\nnew o_width,o_height: " + dim.width + "," + dim.height);
					return n_fixed;
				}
			}
			// flag as good
			fixed.add(ps);
			loader.removeFromUnloadable(ps);
			// Assign new image path with slice info appended
			loader.addedPatchFrom(new_path + slicepath.substring(isl), ps);
			// submit job to regenerate mipmaps in the background
			if (update_mipmaps) loader.regenerateMipMaps(ps);
			//
			data.remove(ps);
			n_fixed++;
		}
		return n_fixed;
	}

	static private boolean fixPatchPath(final Patch patch, final String new_path, final boolean update_mipmaps) {
		try {
			// Open the image header to check that dimensions match
			final Loader loader = patch.getProject().getLoader();
			loader.releaseToFit(Math.max(Loader.MIN_FREE_BYTES, patch.getOWidth() * patch.getOHeight() * 10));
			final Dimension dim = loader.getDimensions(new_path);
			if (null == dim) {
				Utils.log(new StringBuilder("ERROR: could not open image at ").append(new_path).toString()); // preserving backslashes
				return false;
			}
			// Check and set dimensions
			if (dim.width != patch.getOWidth() || dim.height != patch.getOHeight()) {
				Utils.log("ERROR different o_width,o_height for patch " + patch + "\n  at new path " + new_path +
					"\nold o_width,o_height: " + patch.getOWidth() + "," + patch.getOHeight() +
					"\nnew o_width,o_height: " + dim.width + "," + dim.height);
				return false;
			}
			// flag as good
			loader.removeFromUnloadable(patch);
			// Assign new image path
			loader.addedPatchFrom(new_path, patch);
			// submit job to regenerate mipmaps in the background
			if (update_mipmaps) loader.regenerateMipMaps(patch);
			return true;
		} catch (Exception e) {
			IJError.print(e);
			return false;
		}
	}
}
