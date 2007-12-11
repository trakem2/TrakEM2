package ini.trakem2.display;

import ini.trakem2.tree.*;
import ini.trakem2.utils.*;
import ini.trakem2.imaging.PatchStack;

import ij.ImageStack;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import ij.process.ByteProcessor;
import ij.gui.ShapeRoi;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.MenuBar;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.CheckboxMenuItem;
import java.awt.event.ActionListener;
import java.awt.event.ItemListener;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.util.*;
import java.io.File;
import java.awt.geom.AffineTransform;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.vecmath.Point3f;
import javax.vecmath.Color3f;
import javax.vecmath.Vector3f;
import javax.media.j3d.View;

import ij3d.ImageWindow3D;
import ij3d.Image3DUniverse;
import ij3d.Content;
import ij3d.Image3DMenubar;


/** One Display3D instance for each LayerSet (maximum). */
public class Display3D {


	/** Table of LayerSet and Display3D - since there is a one to one relationship.  */
	static private Hashtable ht_layer_sets = new Hashtable();
	/** Table of ProjectThing keys versus meshes, the latter represented by List of triangles in the form of thre econsecutive Point3f in the List.*/
	private Hashtable ht_pt_meshes = new Hashtable();

	private Image3DUniverse universe;

	private Object u_lock = new Object();
	private boolean u_locked = false;


	private ImageWindow3D win;
	private LayerSet layer_set;
	private double width, height;
	private int resample = -1; // unset
	static private final int DEFAULT_RESAMPLE = 4;
	/** If the LayerSet dimensions are too large, then limit to max 2048 for width or height and setup a scale.*/
	private double scale = 1.0;
	static private final int MAX_DIMENSION = 1024;

	private String selected = null;

	/** Defaults to parallel projection. */
	private Display3D(final LayerSet ls) {
		this.layer_set = ls;
		this.universe = new Image3DUniverse(512, 512); // size of the initial canvas, not the universe itself
		this.universe.getViewer().getView().setProjectionPolicy(View.PARALLEL_PROJECTION);
		computeScale(ls);
		this.win = new ImageWindow3D("3D Viewer", this.universe);
		this.win.addWindowListener(new IW3DListener(ls));
		this.win.setMenuBar(new Image3DMenubar(universe));
		// register
		Display3D.ht_layer_sets.put(ls, this);
	}

	private final void lock() {
		//Utils.log("entering lock");
		while (u_locked) { try { u_lock.wait(); } catch (InterruptedException ie) {} }
		u_locked = true;
		//Utils.log("\tlocked");
	}
	private final void unlock() {
		if (u_locked) {
			//Utils.log("unlocking");
			u_locked = false;
			u_lock.notifyAll();
		}
	}

	private class IW3DListener extends WindowAdapter {
		private LayerSet ls;
		IW3DListener(LayerSet ls) {
			this.ls = ls;
		}
		public void windowClosing(WindowEvent we) {
			ht_layer_sets.remove(ls);
		}
	}

	private class D3DMenuBar extends MenuBar implements ActionListener, ItemListener {
		CheckboxMenuItem perspective;
		Menu selection;
		D3DMenuBar() {
			Menu file = new Menu("File");
			this.add(file);
			addItem("Export all (.obj)", file);
			addItem("Export all (.dxf)", file);
			file.addSeparator();
			addItem("Export selected (.obj)", file);
			addItem("Export selected (.dxf)", file);

			Menu main = new Menu("Viewer");
			this.add(main);
			addItem("Reset view", main);
			main.addSeparator();
			addItem("Start animation", main);
			addItem("Stop animation", main);
			main.addSeparator();
			addItem("Start recording", main);
			addItem("Stop recording", main);
			main.addSeparator();
			perspective = new CheckboxMenuItem("Perspective Projection", false);
			perspective.addItemListener(this);
			main.add(perspective);

			selection = new Menu("Selected: none");
			this.add(selection);
			addItem("Remove from view", selection);
		}
		private void addItem(String command, Menu menu) {
			MenuItem item = new MenuItem(command);
			item.addActionListener(this);
			menu.add(item);
		}
		public void itemStateChanged(ItemEvent e) {
			if(e.getSource() == perspective) {
				int policy = perspective.getState() 
							? View.PERSPECTIVE_PROJECTION 
							: View.PARALLEL_PROJECTION;
				universe.getViewer().getView().setProjectionPolicy(policy);
			}
		}
		public void actionPerformed(ActionEvent ae) {
			final String command = ae.getActionCommand();
			if (command.equals("Reset view")) {
				universe.resetView();
			} else if (command.equals("Start recording")) {
				universe.startRecording();
			} else if (command.equals("Stop recording")) {
				ImagePlus movie = universe.stopRecording();
				if (null != movie) movie.show();
			} else if (command.equals("Start animation")) {
				universe.startAnimation();
			} else if (command.equals("Stop animation")) {
				universe.pauseAnimation();
			} else if (command.equals("Remove from view")) {
				// find the ProjectThing with the given id
				if (null == selected) return;
				ProjectThing pt = find(selected);
				if (null != pt) {
					synchronized (u_lock) {
						lock();
						try {
							Displayable displ = (Displayable)pt.getObject();
							universe.removeContent(displ.getTitle() + " #" + displ.getId());
						} catch (Exception e) {
							new IJError(e);
						}
						unlock();
					}
					setSelected(null);
					ht_pt_meshes.remove(pt);
				}
			} else if (command.startsWith("Export all")) {
				export(null, command.substring(13, 16));
			} else if (command.startsWith("Export selected")) {
				ProjectThing pt = find(selected);
				if (null != pt) export(pt, command.substring(18, 21));
			} else {
				Utils.log2("Display3D.menubar: Don't know what to do with command '" + command + "'");
			}
		}
		final void setSelected(String name) {
			if (null == name) name = "none";
			selection.setLabel("Selected: " + name);
		}
	}

	/** Reads the #ID in the name, which is immutable. */
	private ProjectThing find(String name) {
		long id = Long.parseLong(name.substring(name.lastIndexOf('#')+1));
		for (Iterator it = ht_pt_meshes.keySet().iterator(); it.hasNext(); ) {
			ProjectThing pt = (ProjectThing)it.next();
			Displayable d = (Displayable)pt.getObject();
			if (d.getId() == id) {
				return pt;
			}
		}
		return null;
	}

	/** If the layer set is too large in width and height, then set a scale that makes it maximum MAX_DIMENSION in any of the two dimensions. */
	private void computeScale(LayerSet ls) {
		this.width = ls.getLayerWidth();
		this.height = ls.getLayerHeight();
		if (width > MAX_DIMENSION) {
			scale = MAX_DIMENSION / width;
			height *= scale;
			width = MAX_DIMENSION;
		}
		if (height > MAX_DIMENSION) {
			scale = MAX_DIMENSION / height;
			width *= scale;
			height = MAX_DIMENSION;
		}
		Utils.log2("scale, width, height: " + scale + ", " + width + ", " + height);
	}

	/** Get an existing Display3D for the given LayerSet, or create a new one for it (and cache it). */
	static private Display3D get(LayerSet ls) {
		try {
			// test:
			try {
				Class p3f = Class.forName("javax.vecmath.Point3f");
			} catch (ClassNotFoundException cnfe) {
				Utils.log("Java 3D not installed.");
				return null;
			}
			try {
				Class ij3d = Class.forName("ij3d.ImageWindow3D");
			} catch (ClassNotFoundException cnfe) {
				Utils.log("ImageJ_3D_Viewer.jar not installed.");
				return null;
			}
			//
			Object ob = ht_layer_sets.get(ls);
			if (null == ob) {
				ob = new Display3D(ls);
				ht_layer_sets.put(ls, ob);
			}
			return (Display3D)ob;
		} catch (Exception e) {
			new IJError(e);
		}
		return null;
	}

	static public void setWaitingCursor() {
		for (Iterator it = ht_layer_sets.values().iterator(); it.hasNext(); ) {
			((Display3D)it.next()).win.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		}
	}

	static public void doneWaiting() {
		for (Iterator it = ht_layer_sets.values().iterator(); it.hasNext(); ) {
			((Display3D)it.next()).win.setCursor(Cursor.getDefaultCursor());
		}
	}

	/** Scan the ProjectThing children and assign the renderable ones to an existing Display3D for their LayerSet, or open a new one.*/
	static public void show(ProjectThing pt) {
		try {
			// scan the given ProjectThing for 3D-viewable items not present in the ht_meshes
			// So: find arealist, pipe, ball, and profile_list types
			HashSet hs = pt.findBasicTypeChildren();
			if (null == hs || 0 == hs.size()) {
				Utils.log("Node " + pt + " contains no 3D-displayable children");
				return;
			}

			for (Iterator it = hs.iterator(); it.hasNext(); ) {
				// obtain the Displayable object under the node
				ProjectThing child = (ProjectThing)it.next();
				Object obc = child.getObject();
				/*
				if (obc.equals("profile_list")) {
					Utils.log("Display3D can't handle profile lists at the moment.");
					continue;
				}
				*/
				Displayable displ = obc.getClass().equals(String.class) ? null : (Displayable)obc;
				if (null != displ) {
					if (displ.getClass().equals(Profile.class)) {
						Utils.log("Display3D can't handle Bezier profiles at the moment.");
						continue;
					}
					if (!displ.isVisible()) {
						Utils.log("Skipping non-visible node " + displ);
						continue;
					}
				}
				StopWatch sw = new StopWatch();
				// obtain the containing LayerSet
				Display3D d3d = null;
				if (null != displ) d3d = Display3D.get(displ.getLayerSet());
				else if (child.getType().equals("profile_list")) {
					ArrayList al_children = child.getChildren();
					if (null == al_children || 0 == al_children.size()) continue;
					// else, get the first Profile and get its LayerSet
					d3d = Display3D.get(((Displayable)((ProjectThing)al_children.get(0)).getObject()).getLayerSet());
				}
				if (null == d3d) {
					Utils.log("Could not get a proper 3D display for node " + displ);
					return; // java3D not installed most likely
				}
				if (d3d.ht_pt_meshes.contains(child)) {
					Utils.log2("Already here: " + child);
					continue; // already here
				}
				setWaitingCursor(); // the above may be creating a display
				sw.elapsed("after creating and/or retrieving Display3D");
				d3d.addMesh(child, displ);
				sw.elapsed("after creating mesh");
			}
		} catch (Exception e) {
			new IJError(e);
		} finally {
			doneWaiting();
		}
	}

	static public void showOrthoslices(Patch p) {
		Display3D d3d = get(p.getLayerSet());
		d3d.adjustResampling();
		d3d.universe.resetView();
		ImagePlus imp = get8BitStack(p.makePatchStack());
		d3d.universe.addOrthoslice(imp, null, p.getTitle(), new boolean[]{true, true, true}, d3d.resample);
		Content ct = d3d.universe.getContent(p.getTitle());
		ct.toggleLock();
	}

	static public void showVolume(Patch p) {
		Display3D d3d = get(p.getLayerSet());
		d3d.adjustResampling();
		d3d.universe.resetView();
		ImagePlus imp = get8BitStack(p.makePatchStack());
		d3d.universe.addVoltex(imp, null, p.getTitle(), new boolean[]{true, true, true}, d3d.resample);
		Content ct = d3d.universe.getContent(p.getTitle());
		ct.toggleLock();
	}

	/** Returns a stack suitable for the ImageJ 3D Viewer, either 8-bit gray or 8-bit color.
	 *  If the PatchStach is already of the right type, it is returned,
	 *  otherwise a copy is made in the proper type.
	 */
	static private ImagePlus get8BitStack(final PatchStack ps) {
		switch (ps.getType()) {
			case ImagePlus.COLOR_RGB:
				// convert stack to 8-bit color
				return ps.createColor256Copy();
			case ImagePlus.GRAY16:
			case ImagePlus.GRAY32:
				// convert stack to 8-bit
				return ps.createGray8Copy();
			default:
				return ps;
		}
	}

	/** A Material, but avoiding name colisions. */
	static private int mat_index = 1;
	static private class Mtl {
		float alpha = 1;
		float R = 1;
		float G = 1;
		float B = 1;
		String name;
		Mtl(float alpha, float R, float G, float B) {
			this.alpha = alpha;
			this.R = R;
			this.G = G;
			this.B = B;
			name = "mat_" + mat_index;
			mat_index++;
		}
		public boolean equals(Object ob) {
			if (ob instanceof Display3D.Mtl) {
				Mtl mat = (Mtl)ob;
				if (mat.alpha == alpha
				 && mat.R == R
				 && mat.G == G
				 && mat.B == B) {
					return true;
				 }
			}
			return false;
		}
		void fill(StringBuffer sb) {
			sb.append("\nnewmtl ").append(name).append('\n')
			  .append("Ns 96.078431\n")
			  .append("Ka 0.0 0.0 0.0\n")
			  .append("Kd ").append(R).append(' ').append(G).append(' ').append(B).append('\n') // this is INCORRECT but I'll figure out the conversion later
			  .append("Ks 0.5 0.5 0.5\n")
			  .append("Ni 1.0\n")
			  .append("d ").append(alpha).append('\n')
			  .append("illum 2\n\n");
		}
		int getAsSingle() {
			return (int)((R + G + B) / 3 * 255); // something silly
		}
	}

	/** Generates DXF file from a table of ProjectThing and their associated triangles. */
	private String createDXF(Hashtable ht_content) {
		StringBuffer sb_data = new StringBuffer("0\nSECTION\n2\nENTITIES\n");   //header of file
		for (Iterator it = ht_content.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			ProjectThing pt = (ProjectThing)entry.getKey();
			Displayable displ = (Displayable)pt.getObject();
			List triangles = (List)entry.getValue();
			float[] color = displ.getColor().getColorComponents(null);
			Mtl mtl = new Mtl(displ.getAlpha(), color[0], color[1], color[2]);
			writeTrianglesDXF(sb_data, triangles, mtl.name, Integer.toString(mtl.getAsSingle()));
		}
		sb_data.append("0\nENDSEC\n0\nEOF\n");         //TRAILER of the file
		return sb_data.toString();
	}

	/** @param format works as extension as well. */
	private void export(final ProjectThing pt, final String format) {
		if (0 == ht_pt_meshes.size()) return;
		// select file
		File file = Utils.chooseFile("untitled", format);
		if (null == file) return;
		final String name = file.getName();
		String name2 = name;
		if (!name2.endsWith("." + format)) {
			name2 += "." + format;
		}
		File f2 = new File(file.getParent() + "/" + name2);
		int i = 1;
		while (f2.exists()) {
			name2 = name + "_" + i + "." + format;
			f2 = new File(name2);
		}
		Hashtable ht_content = ht_pt_meshes;
		if (null != pt) {
			ht_content = new Hashtable();
			ht_content.put(pt, ht_pt_meshes.get(pt));
		}
		if (format.equals("obj")) {
			String[] data = createObjAndMtl(name2, ht_content);
			Utils.saveToFile(f2, data[0]);
			Utils.saveToFile(new File(f2.getParent() + "/" + name2 + ".mtl"), data[1]);
		} else if (format.equals("dxf")) {
			Utils.saveToFile(f2, createDXF(ht_content));
		}
	}

	/** Wavefront format. Returns the contents of two files: one for materials, another for meshes*/
	private String[] createObjAndMtl(final String file_name, final Hashtable ht_content) {
		StringBuffer sb_obj = new StringBuffer("# TrakEM2 OBJ File\n");
		sb_obj.append("mtllib ").append(file_name).append(".mtl").append('\n');

		Hashtable ht_mat = new Hashtable();

		int j = 1; // Vert indices in .obj files are global, not reset for every object.
				// starting at '1' because vert indices start at one.

		for (Iterator it = ht_content.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next(); // I hate java's gratuituous verbosity
			ProjectThing pt = (ProjectThing)entry.getKey();
			Displayable displ = (Displayable)pt.getObject();
			List triangles = (List)entry.getValue();
			// make material, and see whether it exists already
			float[] color = displ.getColor().getColorComponents(null);
			Mtl mat = new Mtl(displ.getAlpha(), color[0], color[1], color[2]);
			Object mat2 = ht_mat.get(mat);
			if (null != mat2) mat = (Mtl)mat2; // recycling
			else ht_mat.put(mat, mat); // !@#$% Can't get the object in a HashSet easily
			// make list of vertices
			String title = displ.getProject().getMeaningfulTitle(displ).replaceAll(" ", "_").replaceAll("#", "--");
			Hashtable ht_points = new Hashtable(); // because we like inefficiency
			sb_obj.append("o ").append(title).append('\n');
			final int len = triangles.size();
			int[] index = new int[len];
			int k = 0; // iterate over index array, to make faces later
			// j is tag for each new vert, which start at 1 (for some ridiculous reason)
			for (Iterator tt = triangles.iterator(); tt.hasNext(); ) {
				Point3f p = (Point3f)tt.next();
				//no need if coords are not displaced//p = (Point3f)p.clone();
				// check if point already exists
				Object ob = ht_points.get(p);
				if (null != ob) {
					index[k] = ((Integer)ob).intValue();
				} else {
					// new point
					index[k] = j;
					// record
					ht_points.put(p, new Integer(j));
					// append vertex
					sb_obj.append('v').append(' ').append(p.x)
						      .append(' ').append(p.y)
						      .append(' ').append(p.z).append('\n');
					j++;
				}
				k++;
			}
			sb_obj.append("usemtl ").append(mat.name).append('\n');
			sb_obj.append("s 1\n");
			if (0 != len % 3) Utils.log2("WARNING: list of triangles not multiple of 3");
			// print faces
			int len_p = ht_points.size();
			for (int i=0; i<len; i+=3) {
				sb_obj.append('f').append(' ').append(index[i])
					      .append(' ').append(index[i+1])
					      .append(' ').append(index[i+2]).append('\n');
				//if (index[i] > len_p) Utils.log2("WARNING: face vert index beyond range"); // range is from 1 to len_p inclusive
				//if (index[i+1] > len_p) Utils.log2("WARNING: face vert index beyond range");
				//if (index[i+2] > len_p) Utils.log2("WARNING: face vert index beyond range");
				//Utils.log2("j: " + index[i]);
				// checks passed
			}
			sb_obj.append('\n');
		}
		// make mtl file
		StringBuffer sb_mtl = new StringBuffer("# TrakEM2 MTL File\n");
		for (Iterator it = ht_mat.keySet().iterator(); it.hasNext(); ) {
			Mtl mat = (Mtl)it.next();
			mat.fill(sb_mtl);
		}

		return new String[]{sb_obj.toString(), sb_mtl.toString()};
	}

	/** Considers there is only one Display3D for each LayerSet. */
	static public void remove(ProjectThing pt) {
		if (null == pt) return;
		if (null == pt.getObject()) return;
		Object ob = pt.getObject();
		if (!(ob instanceof Displayable)) return;
		Displayable displ = (Displayable)ob;
		Object d3ob = ht_layer_sets.get(displ.getLayerSet());
		if (null == d3ob) {
			// there is no Display3D showing the pt to remove
			return;
		}
		Display3D d3d = (Display3D)d3ob;
		Object ob_mesh = d3d.ht_pt_meshes.remove(pt);
		if (null == ob_mesh) return; // not contained here
		d3d.universe.removeContent(displ.getTitle()); // WARNING if the title changes, problems: will need a table of pt vs title as it was when added to the universe. At the moment titles are not editable for basic types, but this may change in the future.
	}

	static private void writeTrianglesDXF(final StringBuffer sb, final List triangles, final String the_group, final String the_color) {

		final char L = '\n';
		final String s10 = "10\n"; final String s11 = "11\n"; final String s12 = "12\n"; final String s13 = "13\n";
		final String s20 = "20\n"; final String s21 = "21\n"; final String s22 = "22\n"; final String s23 = "23\n";
		final String s30 = "30\n"; final String s31 = "31\n"; final String s32 = "32\n"; final String s33 = "33\n";
		final String triangle_header = "0\n3DFACE\n8\n" + the_group + "\n6\nCONTINUOUS\n62\n" + the_color + L;

		final int len = triangles.size();
		final Point3f[] vert = new Point3f[len];
		triangles.toArray(vert);
		for (int i=0; i<len; i+=3) {

			sb.append(triangle_header)

			.append(s10).append(vert[i].x).append(L)
			.append(s20).append(vert[i].y).append(L)
			.append(s30).append(vert[i].z).append(L)

			.append(s11).append(vert[i+1].x).append(L)
			.append(s21).append(vert[i+1].y).append(L)
			.append(s31).append(vert[i+1].z).append(L)

			.append(s12).append(vert[i+2].x).append(L)
			.append(s22).append(vert[i+2].y).append(L)
			.append(s32).append(vert[i+2].z).append(L)

			.append(s13).append(vert[i+2].x).append(L) // repeated point
			.append(s23).append(vert[i+2].y).append(L)
			.append(s33).append(vert[i+2].z).append(L);
		}
	}

	static private final int MAX_THREADS = Runtime.getRuntime().availableProcessors();
	static private final Vector v_threads = new Vector(MAX_THREADS); // synchronized

	/** Creates a mesh for the given Displayable in a separate Thread. */
	private Thread addMesh(final ProjectThing pt, final Displayable displ) {
		final double scale = this.scale;
		Thread thread = new Thread() {
			public void run() {
				setPriority(Thread.NORM_PRIORITY);
				while (v_threads.size() >= MAX_THREADS) {
					try { Thread.sleep(400); } catch (InterruptedException ie) {}
				}
				v_threads.add(this);
				try {

		// the list 'triangles' is really a list of Point3f, which define a triangle every 3 consecutive points. (TODO most likely Bene Schmid got it wrong: I don't think there's any need to have the points duplicated if they overlap in space but belong to separate triangles.)
		List triangles = null;
		if (displ instanceof AreaList) {
			adjustResampling();
			triangles = ((AreaList)displ).generateTriangles(scale, resample);
			//triangles = removeNonManifold(triangles);
		} else if (displ instanceof Ball) {
			double[][][] globe = Ball.generateGlobe(12, 12);
			triangles = ((Ball)displ).generateTriangles(scale, globe);
		} else if (displ instanceof Pipe) {
			// adjustResampling();  // fails horribly, needs first to correct mesh-generation code
			triangles = ((Pipe)displ).generateTriangles(scale, 12, 1 /*resample*/);
		} else if (null == displ && pt.getType().equals("profile_list")) {
			triangles = Profile.generateTriangles(pt, scale);
		}
		// safety checks
		if (null == triangles) {
			Utils.log("Some error ocurred: can't create triangles for " + displ);
			return;
		}
		if (0 == triangles.size()) {
			Utils.log2("Skipping empty mesh for " + displ.getTitle());
			return;
		}
		if (0 != triangles.size() % 3) {
			Utils.log2("Skipping non-multiple-of-3 vertices list generated for " + displ.getTitle());
			return;
		}
		Color color = null;
		float alpha = 1.0f;
		if (null != displ) {
			color = displ.getColor();
			alpha = displ.getAlpha();
		} else {
			// for profile_list: get from the first (what a kludge)
			Object obp = ((ProjectThing)pt.getChildren().get(0)).getObject();
			if (null == obp) return;
			Displayable di = (Displayable)obp;
			color = di.getColor();
			alpha = di.getAlpha();
		}
		// add to 3D view (synchronized)
		synchronized (u_lock) {
			lock();
			try {
				// craft a unique title (id is always unique)
				String title = null == displ ? pt.toString() : displ.getTitle() + " #" + displ.getId();
				if (ht_pt_meshes.contains(pt)) {
					// remove content from universe
					universe.removeContent(title);
					// no need to remove entry from table, it's overwritten below
				}
				// register mesh
				ht_pt_meshes.put(pt, triangles);
				// ensure proper default transform
				universe.resetView();
				//
				universe.addMesh(triangles, new Color3f(color), title, (float)(1.0 / (width*scale)), 1);
				Content ct = universe.getContent(title);
				ct.setTransparency(1f - alpha);
				ct.toggleLock();
			} catch (Exception e) {
				new IJError(e);
			}
			unlock();
		}

		Utils.log2(pt.toString() + " n points: " + triangles.size());

				} catch (Exception e) {
					new IJError(e);
				} finally {
					v_threads.remove(this);
				}

			} // end of run
		};
		thread.start();
		return thread;
	}

	// This method has the exclusivity in adjusting the resampling value.
	synchronized private final void adjustResampling() {
		if (resample > 0) return;
		final GenericDialog gd = new GenericDialog("Resample");
		gd.addSlider("Resample: ", 1, 20, -1 != resample ? resample : DEFAULT_RESAMPLE);
		gd.showDialog();
		if (gd.wasCanceled()) {
			resample = -1 != resample ? resample : DEFAULT_RESAMPLE; // current or default value
			return;
		}
		resample = ((java.awt.Scrollbar)gd.getSliders().get(0)).getValue();
	}

	/** Checks if there is any Display3D instance currently showing the given Displayable. */
	static public boolean isDisplayed(final Displayable d) {
		final String title = d.getTitle() + " #" + d.getId();
		for (Iterator it = Display3D.ht_layer_sets.values().iterator(); it.hasNext(); ) {
			Display3D d3d = (Display3D)it.next();
			if (null != d3d.universe.getContent(title)) return true;
		}
		return false;
	}

	static public void setColor(final Displayable d, final Color color) {
		if (!isDisplayed(d)) return;
		Display3D d3d = get(d.getLayer().getParent());
		if (null == d3d) return; // no 3D displays open
		Content content = d3d.universe.getContent(d.getTitle() + " #" + d.getId());
		//Utils.log2("content: " + content);
		if (null != content) content.setColor(new Color3f(color));
	}

	static public void setTransparency(final Displayable d, final float alpha) {
		if (!isDisplayed(d)) return;
		Layer layer = d.getLayer();
		if (null == layer) return; // some objects have no layer, such as the parent LayerSet.
		Object ob = ht_layer_sets.get(layer.getParent());
		if (null == ob) return;
		Display3D d3d = (Display3D)ob;
		Content content = d3d.universe.getContent(d.getTitle() + " #" + d.getId());
		if (null != content) content.setTransparency(1 - alpha);
	}

	/** Remake the mesh for the Displayable in a separate Thread, if it's included in a Display3D
	 *  (otherwise returns null). */
	static public Thread update(final Displayable d) {
		Layer layer = d.getLayer();
		if (null == layer) return null; // some objects have no layer, such as the parent LayerSet.
		Object ob = ht_layer_sets.get(layer.getParent());
		if (null == ob) return null;
		Display3D d3d = (Display3D)ob;
		return d3d.addMesh(d.getProject().findProjectThing(d), d);
	}
}
