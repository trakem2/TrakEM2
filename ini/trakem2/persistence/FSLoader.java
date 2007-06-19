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

import ij.IJ;
import ij.ImagePlus;
import ini.trakem2.imaging.VirtualStack; //import ij.VirtualStack; // only after 1.38q
import ij.io.*;
import ij.process.ImageProcessor;
import ij.gui.YesNoCancelDialog;
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
import ini.trakem2.tree.Attribute;
import ini.trakem2.tree.LayerThing;
import ini.trakem2.tree.ProjectAttribute;
import ini.trakem2.tree.ProjectThing;
import ini.trakem2.tree.TemplateThing;
import ini.trakem2.tree.TemplateAttribute;
import ini.trakem2.tree.Thing;
import ini.trakem2.tree.TrakEM2MLParser;
import ini.trakem2.tree.DTDParser;
import ini.trakem2.utils.*;
import ini.trakem2.io.*;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.geom.Area;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import javax.swing.JMenuItem;
import javax.swing.JMenu;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.KeyStroke;

import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.InputSource;
import org.xml.sax.Attributes;

import javax.xml.parsers.SAXParserFactory;
import javax.xml.parsers.SAXParser;


/** A class to rely on memory only for now; except images which are rolled from a folder or their original location and flushed when memory is needed for more. Ideally there would be a given folder for storing items temporarily of permanently as the "project folder", but I haven't implemented it. */
public class FSLoader extends Loader {

	/** Contains all project objects that call addToDatabase */
	private Hashtable ht_dbo = null;
	private Hashtable ht_paths = null;
	/** For saving and overwriting. */
	private String project_xml_path = null;

	public FSLoader() {
		super(); // register
		ht_dbo = new Hashtable();
		ht_paths = new Hashtable();
		// ask for a xml path to save to
		//SaveDialog sd = new SaveDialog();
		super.v_loaders.remove(this); //will be readded on successful open
	}

	public String getProjectXMLPath() {
		return project_xml_path;
	}

	/** Returns TMLHandler.getProjectData() . If the path is null it'll be asked for. */
	public Object[] openXMLProject(final String path) {
		if (null == path) {
			String user = System.getProperty("user.name");
			OpenDialog od = new OpenDialog("Select Project",
							(user.equals("albert") || user.equals("cardona")) ? "/home/" + user + "/temp" : OpenDialog.getDefaultDirectory(),
							null);
			String file = od.getFileName();
			if (null == file || file.toLowerCase().startsWith("null")) return null;
			this.project_xml_path = od.getDirectory() + "/" + file;
		} else {
			this.project_xml_path = path;
		}

		for (Iterator it = v_loaders.iterator(); it.hasNext(); ) {
			Loader lo = (Loader)it.next();
			if (lo instanceof FSLoader && ((FSLoader)lo).project_xml_path.equals(this.project_xml_path)) {
				Utils.showMessage("The project is already open.");
				return null;
			}
		}

		// parse file:
		InputStream i_stream = null;
		TMLHandler handler = new TMLHandler(this.project_xml_path, this);
		if (handler.isUnreadable()) {
			handler = null;
		} else {
			try {
				SAXParserFactory factory = SAXParserFactory.newInstance();
				factory.setValidating(true);
				SAXParser parser = factory.newSAXParser();
				i_stream = new BufferedInputStream(new FileInputStream(this.project_xml_path));
				InputSource input_source = new InputSource(i_stream);
				setMassiveMode(true);
				parser.parse(input_source, handler);
			} catch (java.io.FileNotFoundException fnfe) {
				Utils.log("ERROR: File not found: " + path);
				handler = null;
			} catch (Exception e) {
				new IJError(e);
				handler = null;
			} finally {
				setMassiveMode(false);
				if (null != i_stream) {
					try {
						i_stream.close();
					} catch (Exception e) {
						new IJError(e);
					}
				}
			}
		}
		if (null == handler) {
			Utils.showMessage("Error when reading the project .xml file.");
			return null;
		}

		Object[] data = handler.getProjectData();
		if (null == data) {
			Utils.showMessage("Error when parsing the project .xml file.");
			return null;
		}
		// else, good
		super.v_loaders.add(this);
		return data;
	}

	public boolean isReady() {
		return null != ht_dbo;
	}

	public void destroy() {
		super.destroy();
		Utils.showStatus("");
	}

	public long getNextId() {
		// examine the hastable for existing ids
		int n = ht_dbo.size();
		if (0 != n) {
			long[] ids = new long[ht_dbo.size()];
			int i = 0;
			for (Enumeration e = ht_dbo.keys(); e.hasMoreElements(); i++) {
				ids[i] = ((Long)e.nextElement()).longValue();
			}
			Arrays.sort(ids);
			return ids[n-1] + 1;
		}
		return 1;
	}

	public double[][][] fetchBezierArrays(long id) { // profiles are loaded in full from the XML file
		return null;
	}

	public ArrayList fetchPipePoints(long id) {
		return null;
	}

	public ArrayList fetchBallPoints(long id) {
		return null;
	}

	public Area fetchArea(long area_list_id, long layer_id) { // loaded in full from XML file
		return null;
	}

	public ImagePlus fetchImagePlus(Patch p) {
		return fetchImagePlus(p, true);
	}

	public ImagePlus fetchImagePlus(Patch p, boolean create_snap) {
		synchronized (db_lock) {
			lock();
			ImagePlus imp = imps.get(p.getId());
			String slice = null;
			String path = null;
			try {
				path = getAbsolutePath(p);
				int i_sl = -1;
				if (null != path) i_sl = path.lastIndexOf("-----#slice=");
				if (-1 != i_sl) {
					// activate proper slice
					//Utils.log("setting " + p + " to slice " +path.substring(i_sl + 12));
					if (null != imp) {
						// check that the stack is large enough (user may have changed it)
						final int ia = Integer.parseInt(path.substring(i_sl + 12));
						if (ia <= imp.getNSlices()) {
							imp.setSlice(ia);
						} else {
							unlock();
							return null; // beyond bonds!
						}
					}
				}
				if (null != imp) {
					if (null != imp.getProcessor() && null != imp.getProcessor().getPixels()) {
						unlock();
						//Utils.log2("A1 returning " + imp + " at slice" + imp.getCurrentSlice() + " for path " + path);
						return imp;
					} else {
						imp.flush(); // can't hurt: it does, runs GC
					}
				}
				releaseMemory(); // ensure there is a minimum % of free memory
				if (null != path) {
					if (-1 != i_sl) {
						slice = path.substring(i_sl);
						// set path proper
						path = path.substring(0, i_sl);
					}
					imp = openImage(path);
					preProcess(imp);
					//Utils.log2("opened " + path);
					//Utils.printCaller(this, 10);
					if (null == imp) {
						Utils.log("FSLoader.fetchImagePlus: no image exists for patch  " + p + "  at path " + path);
						unlock();
						return null;
					}
					//Utils.log("debug: slice is " + slice);
					// update all clients of the stack, if any
					if (null != slice) {
						String rel_path = getPath(p); // possibly relative
						int r_isl = rel_path.lastIndexOf("-----#slice");
						if (-1 != r_isl) rel_path = rel_path.substring(0, r_isl); // should always happen
						//Utils.log("rel_path is " + rel_path);
						for (Iterator it = ht_paths.entrySet().iterator(); it.hasNext(); ) {
							Map.Entry entry = (Map.Entry)it.next();
							String str = (String)entry.getValue(); // this is like calling getPath(p)
							Utils.log2("processing " + str);
							if (0 != str.indexOf(rel_path)) {
								Utils.log2("SKIP str is: " + str + "\t but path is: " + rel_path);
								continue; // get only those whose path is identical, of course!
							}
							int isl = str.lastIndexOf("-----#slice=");
							if (-1 != isl) {
								//int i_slice = Integer.parseInt(str.substring(isl + 12));
								Patch pa = (Patch)entry.getKey();
								imps.put(pa.getId(), imp);
								imp.setSlice(Integer.parseInt(str.substring(isl + 12)));
								pa.putMinAndMax(imp);
								if (create_snap) {
									unlock();
									Image awt = pa.createImage(imp); // will call cacheAWT
									lock();
									snaps.put(pa.getId(), Snapshot.createSnap(pa, awt, Snapshot.SCALE));
								}
							}
						}
						// set proper active slice
						imp.setSlice(Integer.parseInt(slice.substring(12)));
					} else {
						// for non-stack images
						p.putMinAndMax(imp); // non-destructive contrast: min and max
						imps.put(p.getId(), imp);
					}
					// need to create the snapshot
					//Utils.log2("create_snap: " + create_snap + ", " + slice);
					if (create_snap && null == slice) {
						unlock();
						Image awt = p.createImage(imp);
						lock();
						//The line below done at p.createImage() because it calls cacheAWT
						//awts.put(p.getId(), awt);
						snaps.put(p.getId(), Snapshot.createSnap(p, awt, Snapshot.SCALE)); //awt.getScaledInstance((int)Math.ceil(p.getWidth() * Snapshot.SCALE), (int)Math.ceil(p.getHeight() * Snapshot.SCALE), Snapshot.SCALE_METHOD));
					}
				}
			} catch (Exception e) {
				new IJError(e);
			}
			unlock();
			//Utils.log2("A2 returning " + imp + " for path " + path + slice);
			return imp;
		}
	}

	public Image fetchSnapshot(Patch p) {
		synchronized (db_lock) {
			lock();
			Image snap = snaps.get(p.getId());
			if (null == snap) {
				Image awt = awts.get(p.getId()); // automatically places it at the end of the FIFO
				if (null == awt) {
					unlock();
					fetchImagePlus(p, true); // will create snapshot
					lock();
				} else {
					try {
						releaseMemory(); // mild attempt
						snap = Snapshot.createSnap(p, awt, Snapshot.SCALE); //awt.getScaledInstance((int)(p.getWidth() * Snapshot.SCALE), (int)(p.getHeight() * Snapshot.SCALE), Snapshot.SCALE_METHOD);
						snaps.put(p.getId(), snap);
					} catch (Exception e) {
						unlock();
						return NOT_FOUND;
					}
				}
			}
			unlock();
			if (null == snap) return NOT_FOUND;
			return snap;
		}
	}

	public Object[] fetchLabel(DLabel label) {
		return null;
	}

	/** Does NOT return the original image, but the working. The original image is not stored if a working image exists. */
	synchronized public ImagePlus fetchOriginal(Patch patch) {
		Utils.showMessage("The original image was NOT stored.");
		return fetchImagePlus(patch);
	}

	public void prepare(Layer layer) {
		//Utils.log2("FSLoader.prepare(Layer): not implemented.");
		super.prepare(layer);
	}

	/* GENERIC, from DBObject calls. Records the id of the object in the Hashtable ht_dbo. */
	public boolean addToDatabase(DBObject ob) {
		// cheap version: keep in memory in a hashtable
		synchronized (db_lock) {
			lock();
			setChanged(true);
			Long lid = new Long(ob.getId());
			if (!ht_dbo.contains(lid)) {
				ht_dbo.put(lid, ob);
			}
			unlock();
		}
		return true;
	}

	public boolean updateInDatabase(DBObject ob, String key) {
		synchronized (db_lock) {
			lock();
			setChanged(true);
			unlock();
		}
		if (ob instanceof Patch) {
			try {
				Patch p = (Patch)ob;
				String path = getAbsolutePath(p); //(String)ht_paths.get(ob);
				String slice = null;
				// path can be null if the image is pasted, or from a copy
				if (null != path) {
					int i_sl = path.lastIndexOf("-----#slice=");
					if (-1 != i_sl) {
						slice = path.substring(i_sl);
						path = path.substring(0, i_sl);
					}
				}
				if (key.equals("tiff_working")) {
					boolean overwrite = null != path;
					if (overwrite) {
						YesNoCancelDialog yn = new YesNoCancelDialog(IJ.getInstance(), "Overwrite?", "Overwrite '" + p.getTitle() + "'\nat " + path + "' ?");
						if (yn.cancelPressed()) {
							return false;
						} else if (!yn.yesPressed()) { // so, 'no' button pressed
							overwrite = false;
						}
					}
					if (overwrite) {
						// save as a separate image and update path
						path = path.substring(0, path.lastIndexOf('/') + 1) + p.getTitle();
						if (path.length() -4 == path.lastIndexOf(".tif")) {
							path = path.substring(0, path.length() -4);
						}
						// remove previous time stamp, if any
						int i = 0;
						for (i=path.length()-1; i>-1; i--) {
							if (Character.isDigit(path.charAt(i))) continue;
							break;
						}
						if (-1 != i && path.length() - i >= 12) { // otherwise it may not be a time stamp
							path = path.substring(0, i+1);
						} else {
							path += "_";
						}
						path += System.currentTimeMillis() + ".tif";
						path = super.exportImage(p, path, true);
						Utils.log2("Saved original " + ht_paths.get(p) + "\n\t at: " + path);
						// make path relative if possible
						if (null != project_xml_path) { // project may be unsaved
							File fxml = new File(project_xml_path);
							String parent_dir = fxml.getParent().replace('\\', '/');
							if (!parent_dir.endsWith("/")) parent_dir += "/";
							if (0 == path.indexOf((String)ht_paths.get(p))) {
								path = path.substring(path.length());
							}
						}
						// update paths' hashtable
						if (null == slice) {
							updatePatchPath(p, path);
						} else {
							// update all slices
							for (Iterator it = ht_paths.entrySet().iterator(); it.hasNext(); ) {
								Map.Entry entry = (Map.Entry)it.next();
								String str = (String)entry.getValue();
								int isl = str.lastIndexOf("-----#slice=");
								if (-1 != isl) {
									entry.setValue(path + str.substring(isl));
								}
							}
						}
						return true;
					} else {
						// else 'yes' button pressed: overwrite image file
						Utils.log2("overwriting image at " + path);
						exportImage(p, path, true);
					}
				}
			} catch (Exception e) {
				new IJError(e);
				return false;
			}
		}
		return true;
	}

	public boolean removeFromDatabase(DBObject ob) {
		synchronized (db_lock) {
			lock();
			setChanged(true);
			// remove from the hashtable
			long loid = ob.getId();
			Long lid = new Long(loid);
			ht_dbo.remove(lid);
			if (ob instanceof Patch) {
				/*
				// remove the image from the dir and the table
				String path = (String)ht_paths.get(ob);
				if (null == path) {
					Utils.log("ERROR FSLoader.removeFromDatabase: null path for id=" + ob.getId());
					unlock();
					return false;
				}
				try {
					unlock(); // !@#$%^&*
					YesNoCancelDialog yn = new YesNoCancelDialog(IJ.getInstance(), "Delete file?", "Delete image file '" + path + "' as well?");
					lock();
					if (yn.yesPressed()) {
						new File(path).delete();
					}
					// in any case:
					ht_paths.remove(ob);
				} catch (Exception e) {
					Utils.log("Could NOT delete the image file " + path + " for Patch with id=" + ob.getId());
					new IJError(e);
					unlock();
					return false;
				}*/

				// STRATEGY change: images are not owned by the FSLoader.
				ht_paths.remove(ob);
				Image snap = snaps.remove(loid);
				if (null != snap) snap.flush();
				Image awt = awts.remove(loid);
				if (null != awt) awt.flush();
				ImagePlus imp = imps.remove(loid);
				if (null != imp) {
					if (imp.getStackSize() > 1) { // avoid calling gc() if unnecessary
						if (null == imp.getProcessor()) {}
						else if (null == imp.getProcessor().getPixels()) {}
						else imp.flush();
					} else {
						imp.flush();
					}
				}
			}
			unlock();
		}
		return true;
	}

	private String getAbsolutePath(final Patch patch) {
		Object ob = ht_paths.get(patch);
		if (null == ob) return null;
		String path = (String)ob;
		// substract slice info if there
		int i_sl = path.lastIndexOf("-----#slice=");
		String slice = null;
		if (-1 != i_sl) {
			slice = path.substring(i_sl);
			path = path.substring(0, i_sl);
		}
		final int i_slash = path.indexOf(File.separatorChar);
		if ((!IJ.isWindows() && 0 != i_slash) || (IJ.isWindows() && 1 != path.indexOf(":/"))) {
			// path is relative
			Utils.log2("Where are in and project_xml_path is  " + project_xml_path);
			File fxml = new File(project_xml_path);
			String parent_dir = fxml.getParent();
			String tmp = parent_dir + "/" + path;
			if (new File(tmp).exists()) {
				path = tmp;
			} else {
				// try *_images folder
				final String dir = extractRelativeFolderPath(new File(project_xml_path));
				if (null != dir) path = dir + "/" + path;
			}
		}
		// reappend slice info if existent
		if (null != slice) path += slice;
		return path;
	}

	/** All backslashes are converted to slashes to avoid havoc in MSWindows. */
	public void addedPatchFrom(String path, final Patch patch) {
		if (null == path) {
			Utils.log("Null path for patch: " + patch);
			return;
		}
		updatePatchPath(patch, path);
	}

	/** This method has the exclusivity in calling ht_paths.put, because it ensures the path won't have escape characters. */
	private final void updatePatchPath(final Patch patch, String path) { // reversed order in purpose, relative to addedPatchFrom
		// avoid W1nd0ws nightmares
		path = path.replace('\\', '/'); //All("\\\\", "/"); // The \\\\ is just a single \ in the final regex match
		// if path is absolute, try to make it relative
		path = makeRelativePath(path);
		// store
		ht_paths.put(patch, path);
		//Utils.log2("Updated patch path " + ht_paths.get(patch) + " for patch " + patch);
	}

	/** Overriding superclass method;  if a path for the given Patch exists in the ht_paths Hashtable, it will be returned (meaning, the image exists already); if not, then an attempt is made to save it in the given path. The overwrite flag is used in the second case, to avoid creating a new image every time the Patch pixels are edited. The original image is never overwritten in any case. */
	public String exportImage(Patch patch, String path, boolean overwrite) {
		Object ob = ht_paths.get(patch);
		if (null == ob) {
			// means, the image has no related source file
			if (null == path) {
				if (null == project_xml_path) {
					this.project_xml_path = save(patch.getProject());
					if (null == project_xml_path) {
						return null;
					}
				}
				path = makePatchesDir(new File(project_xml_path)) + "/" + patch.getTitle().replace(' ', '_');
			}
			path = super.exportImage(patch, path, overwrite);
			// record
			Utils.log2("patch: " + patch + "  and path: " + path);
			updatePatchPath(patch, path);
			return path;
		} else {
			//Utils.printCaller(this, 7);
			//Utils.log2("FSLoader.exportImage: path is " + ob.toString());
			return (String)ob; // the path of the source image file
		}
	}

	/** Overwrites the XML file. If some images do not exist in the file system, a directory with the same name of the XML file plus an "_images" tag appended will be created and images saved there. */
	public String save(Project project) {
		if (null == project_xml_path) {
			String xml_path = super.saveAs(project);
			if (null == xml_path) return null;
			else {
				this.project_xml_path = xml_path;
				return this.project_xml_path;
			}
		} else {
			File fxml = new File(project_xml_path);
			return super.export(project, fxml, false);
		}
	}

	/** Returns the stored path for the given Patch image, which may be relative.*/
	public String getPath(final Patch patch) {
		final Object ob = ht_paths.get(patch);
		if (null == ob) return null;
		return (String)ob;
	}
	/** Takes the given path and tries to makes it relative to this instance's project_xml_path, if possible. Otherwise returns the argument as is. */
	private String makeRelativePath(String path) {
		if (null == project_xml_path) {
			//unsaved project//Utils.log2("WARNING: null project_xml_path at makeRelativePath");
			return path;
		}
		if (null == path) {
			Utils.log2("WARNING: null path at makeRelativePath");
			return null;
		}
		// fix W1nd0ws paths
		path = path.replace('\\', '/'); // the \\\\ is just a single \, because the regex pattern creation reinterprets it
		// remove slice tag
		String slice = null;
		int isl = path.lastIndexOf("-----#slice");
		if (-1 != isl) {
			slice = path.substring(isl);
			path = path.substring(0, isl);
		}
		//
		if (! (path.startsWith("/") || 1 == path.indexOf(":/"))) {
			//Utils.log2("already relative");
			if (-1 != isl) path += slice;
			return path; // already relative
		}
		// the long and verbose way, to be cross-platform
		File xf = new File(project_xml_path);
		if (new File(path).getParentFile().equals(xf.getParentFile())) {
			path = path.substring(xf.getParent().length());
			// remove prepended file separator, if any, to label the path as relative
			if (0 == path.indexOf('/')) path = path.substring(1);
		}
		if (-1 != isl) path += slice;
		//Utils.log("made relative path: " + path);
		return path;
	}

	/** Adds a "Save" and "Save as" menu items. */
	public void setupMenuItems(final JMenu menu, final Project project) {
		ActionListener listener = new ActionListener() {
			public void actionPerformed(ActionEvent ae) {
				String command = ae.getActionCommand();
				if (command.equals("Save")) {
					save(project);
				} else if (command.equals("Save as...")) {
					saveAs(project);
				}
			}
		};
		JMenuItem item;
		item = new JMenuItem("Save"); item.addActionListener(listener); menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0, true));
		item = new JMenuItem("Save as..."); item.addActionListener(listener); menu.add(item);
	}

	/** Returns the last Patch. */
	protected Patch importStackAsPatches(final Project project, final Layer first_layer, final ImagePlus imp_stack, final boolean as_copy, final String filepath) {
		Utils.log2("FSLoader.importStackAsPatches filepath=" + filepath);
		String target_dir = null;
		if (as_copy) {
			DirectoryChooser dc = new DirectoryChooser("Folder to save images");
			target_dir = dc.getDirectory();
			if (null == target_dir) return null; // user canceled dialog
			if (target_dir.length() -1 != target_dir.lastIndexOf(File.separatorChar)) {
				target_dir += "/";
			}
		}

		final boolean virtual = imp_stack.getStack().isVirtual();

		int pos_x = (int)first_layer.getLayerWidth()/2 - imp_stack.getWidth()/2;
		int pos_y = (int)first_layer.getLayerHeight()/2 - imp_stack.getHeight()/2;
		final double thickness = first_layer.getThickness();
		final String title = Utils.removeExtension(imp_stack.getTitle()).replace(' ', '_');
		Utils.showProgress(0);
		Patch previous_patch = null;
		final int n = imp_stack.getStackSize();
		for (int i=1; i<=n; i++) {
			Layer layer = first_layer;
			double z = first_layer.getZ() + (i-1) * thickness;
			if (i > 1) layer = first_layer.getParent().getLayer(z, thickness, true); // will create new layer if not found
			if (null == layer) {
				Utils.log("Display.importStack: could not create new layers.");
				return null;
			}
			String patch_path = null;

			ImagePlus imp_patch_i = null;
			if (virtual) { // because we love inefficiency, every time all this is done again
				VirtualStack vs = (VirtualStack)imp_stack.getStack();
				String vs_dir = vs.getDirectory().replace('\\', '/');
				if (!vs_dir.endsWith("/")) vs_dir += "/";
				String iname = vs.getFileName(i);
				patch_path = vs_dir + iname;
				imp_patch_i = opener.openImage(vs_dir, iname);
			} else {
				ImageProcessor ip = imp_stack.getStack().getProcessor(i);
				if (as_copy) ip = ip.duplicate();
				imp_patch_i = new ImagePlus(title + "__slice=" + i, ip);
			}
			preProcess(imp_patch_i);

			String label = imp_stack.getStack().getSliceLabel(i);
			if (null == label) label = "";
			Patch patch = null;
			if (as_copy) {
				patch_path = target_dir + imp_patch_i.getTitle() + ".zip";
				new FileSaver(imp_patch_i).saveAsZip(patch_path);
				patch = new Patch(project, label + " " + title + " " + i, pos_x, pos_y, imp_patch_i);
			} else if (virtual) {
				patch = new Patch(project, label, pos_x, pos_y, imp_patch_i);
			} else {
				patch_path = filepath + "-----#slice=" + i;
				//Utils.log2("path is "+ patch_path);
				patch = new Patch(project, getNextId(), label + " " + title + " " + i, pos_x, pos_y, imp_stack.getWidth(), imp_stack.getHeight(), 0, imp_stack.getType(), false, imp_stack.getProcessor().getMin(), imp_stack.getProcessor().getMax());
				patch.addToDatabase();
				//Utils.log2("type is " + imp_stack.getType());
			}
			addedPatchFrom(patch_path, patch);
			if (!as_copy) {
				cache(patch, imp_stack); // uses the entire stack, shared among all Patch instances
			}
			if (null != previous_patch) patch.link(previous_patch);
			layer.add(patch);
			previous_patch = patch;
			Utils.showProgress(i * (1.0 / n));
		}
		Utils.showProgress(1.0);
		// update calibration
		//if ( TODO
		// return the last patch
		return previous_patch;
	}
}
