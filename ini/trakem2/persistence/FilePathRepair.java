package ini.trakem2.persistence;

import java.io.File;
import java.util.Hashtable;
import java.util.HashSet;
import java.util.Vector;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BoxLayout;
import javax.swing.table.AbstractTableModel;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.LineBorder;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.io.OpenDialog;

import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.display.Patch;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.Dispatcher;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

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
			frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
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
			final String path = patch.getFilePath();
			final int i = path.lastIndexOf("-----#slice=");
			vpath.add(-1 == i ? path : path.substring(0, i));
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
						gd.addCheckbox("Fix others with identical parent directory", true);
						gd.addCheckbox("Update mipmaps for each fixed path", false);
						gd.showDialog();
						if (!gd.wasCanceled()) {
							fixPath(table, data, row, gd.getNextBoolean(), gd.getNextBoolean());
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

	static private void fixPath(final JTable table, final PathTableModel data, final int row, final boolean fix_similar, final boolean update_mipmaps) throws Exception {
		synchronized (projects) {
			final Patch patch = data.vp.get(row);
			if (null == patch) return;
			final String old_path = patch.getImageFilePath();
			final File f = new File(old_path);
			if (f.exists()) {
				Utils.log("File exists for " + patch + " at " + f.getAbsolutePath() + "\n  --> not updating.");
				data.remove(row);
				return;
			}
			// Else, pop up file dialog
			OpenDialog od = new OpenDialog("Select image file", OpenDialog.getDefaultDirectory(), null);
			final String dir = od.getDirectory();
			final String filename = od.getFileName();
			if (null == dir) return; // dialog was canceled
			final String path = new StringBuffer(dir).append(filename).toString(); // save concat, no backslash altered
			if (!fixPatchPath(patch, path, update_mipmaps)) {
				return;
			}
			// Remove from table
			String wrong_parent_path = new File(data.remove(row)).getParent();
			if (!wrong_parent_path.endsWith(File.separator)) wrong_parent_path = new StringBuffer(wrong_parent_path).append(File.separatorChar).toString(); // backslash-safe
			String good_parent_path = dir;
			if (!dir.endsWith(File.separator)) good_parent_path = new StringBuffer(good_parent_path).append(File.separatorChar).toString(); // backslash-safe
			// Check for similar parent paths and see if they can be fixed
			if (fix_similar) {
				for (int i=data.vp.size() -1; i>-1; i--) {
					final String wrong_path = data.vpath.get(i);
					final Patch p = data.vp.get(i);
					if (wrong_path.startsWith(wrong_parent_path)) {
						// try to fix as well
						File file = new File(new StringBuffer(good_parent_path).append(wrong_path.substring(wrong_parent_path.length())).toString());
						if (file.exists()) {
							if (fixPatchPath(p, file.getAbsolutePath(), update_mipmaps)) {
								data.remove(p); // not by 'i' but by Patch, since if some fail the order is not the same
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
		}
	}

	static private boolean fixPatchPath(final Patch patch, final String new_path, final boolean update_mipmaps) {
		try {
			// Open the image header to check that dimensions match
			final Loader loader = patch.getProject().getLoader();
			loader.releaseToFit(Math.max(Loader.MIN_FREE_BYTES, patch.getOWidth() * patch.getOHeight() * 10));
			final Dimension dim = loader.getDimensions(new_path);
			if (null == dim) {
				Utils.log(new StringBuffer("ERROR: could not open image at ").append(new_path).toString()); // preserving backslashes
				return false;
			}
			// Check and set dimensions
			if (dim.width != patch.getOWidth() || dim.height != patch.getOHeight()) {
				Utils.log("ERROR different o_width,o_height for patch " + patch + "\n  at new path " + new_path);
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
