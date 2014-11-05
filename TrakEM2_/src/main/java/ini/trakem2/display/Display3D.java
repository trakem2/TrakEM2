package ini.trakem2.display;

import customnode.CustomLineMesh;
import customnode.CustomMesh;
import customnode.CustomMultiMesh;
import customnode.CustomTriangleMesh;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.measure.Calibration;
import ij3d.Content;
import ij3d.Image3DUniverse;
import ij3d.ImageWindow3D;
import ij3d.UniverseListener;
import ini.trakem2.display.d3d.ControlClickBehavior;
import ini.trakem2.display.d3d.Display3DGUI;
import ini.trakem2.imaging.PatchStack;
import ini.trakem2.tree.ProjectThing;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.vector.VectorString3D;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.media.j3d.PolygonAttributes;
import javax.media.j3d.Transform3D;
import javax.media.j3d.View;
import javax.vecmath.Color3f;
import javax.vecmath.Point3f;


/** One Display3D instance for each LayerSet (maximum). */
public final class Display3D {

	/** Table of LayerSet and Display3D - since there is a one to one relationship.  */
	static private Hashtable<LayerSet,Display3D> ht_layer_sets = new Hashtable<LayerSet,Display3D>();
	/**Control calls to new Display3D. */
	static private Object htlock = new Object();

	/** The sky will fall on your head if you modify any of the objects contained in this table -- which is a copy of the original, but the objects are the originals. */
	static public Hashtable<LayerSet,Display3D> getMasterTable() {
		return new Hashtable<LayerSet,Display3D>(ht_layer_sets);
	}

	/** Table of ProjectThing keys versus names of Content objects in the universe. */
	private Map<ProjectThing,String> ht_pt_meshes = Collections.synchronizedMap(new HashMap<ProjectThing,String>());

	private Image3DUniverse universe;

	private LayerSet layer_set;
	/** The dimensions of the LayerSet in 2D. */
	private double width, height;
	private int resample = -1; // unset
	static private final int DEFAULT_RESAMPLE = 4;
	/** If the LayerSet dimensions are too large, then limit to max 2048 for width or height and setup a scale.*/
	private final double scale = 1.0; // OBSOLETE: meshes are now generated with imglib ShapeList images.


	// To fork away from the EventDispatchThread
	static private ExecutorService launchers = Utils.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), "Display3D-launchers");

	// To build meshes, or edit them
	private ExecutorService executors = Utils.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), "Display3D-executors");

	/*
	static private KeyAdapter ka = new KeyAdapter() {
		public void keyPressed(KeyEvent ke) {
			// F1 .. F12 keys to set tools
			ProjectToolbar.keyPressed(ke);
		}
	};
	*/

	/** Defaults to parallel projection. */
	private Display3D(final LayerSet ls) {
		this.layer_set = ls;
		this.width = ls.getLayerWidth();
		this.height = ls.getLayerHeight();
		this.universe = new Image3DUniverse(512, 512); // size of the initial canvas, not the universe itself
		this.universe.getViewer().getView().setProjectionPolicy(View.PERSPECTIVE_PROJECTION); // (View.PERSPECTIVE_PROJECTION);
		
		//this.universe.show();
		
		Display3DGUI gui = new Display3DGUI(this.universe);
		ImageWindow3D win = gui.init();
		this.universe.init(win);
		win.pack();
		win.setVisible(true);
		
		this.universe.getWindow().addWindowListener(new IW3DListener(this, ls));
		this.universe.getWindow().setTitle(ls.getProject().toString() + " -- 3D Viewer");
		// it ignores the listeners:
		//preaddKeyListener(this.universe.getWindow(), ka);
		//preaddKeyListener(this.universe.getWindow().getCanvas(), ka);

		// register
		Display3D.ht_layer_sets.put(ls, this);

		// Add a behavior to catch control + mouse-click on
		// objects in the 3D viewer and centre the front Display
		// on that point:
		this.universe.addInteractiveBehavior(new ControlClickBehavior(universe, ls));
		
		this.universe.addUniverseListener(new UniverseListener() {
			@Override
			public void universeClosed() {
				synchronized (ht_pt_meshes) {
					ht_pt_meshes.clear();
				}
			}
			@Override
			public void transformationUpdated(View arg0) {
			}
			@Override
			public void transformationStarted(View arg0) {
			}
			@Override
			public void transformationFinished(View arg0) {
			}
			@Override
			public void contentSelected(Content arg0) {
				// TODO could select in TrakEM2's Display
			}
			@Override
			public void contentRemoved(Content arg0) {
				String name = arg0.getName();
				synchronized (ht_pt_meshes) {
					for (final Iterator<Map.Entry<ProjectThing,String>> it = ht_pt_meshes.entrySet().iterator(); it.hasNext(); ) {
						if (name.equals(it.next().getValue())) {
							it.remove();
							break;
						}
					}
				}
			}
			@Override
			public void contentChanged(Content arg0) {
			}
			@Override
			public void contentAdded(Content arg0) {
			}
			@Override
			public void canvasResized() {
			}
		});
	}

	/*
	private void preaddKeyListener(Component c, KeyListener kl) {
		KeyListener[] all = c.getKeyListeners();
		if (null != all) {
			for (KeyListener k : all) c.removeKeyListener(k);
		}
		c.addKeyListener(kl);
		if (null != all) {
			for (KeyListener k : all) c.addKeyListener(k);
		}
	}
	*/

	public Image3DUniverse getUniverse() {
		return universe;
	}

	/* Take a snapshot know-it-all mode. Each Transform3D given as argument gets assigned to the (nearly) homonimous TransformGroup, which have the following relationships:
	 *
	 *  scaleTG contains rotationsTG
	 *  rotationsTG contains translateTG
	 *  translateTG contains centerTG
	 *  centerTG contains the whole scene, with all meshes, etc.
	 *
	 *  Any null arguments imply the current transform in the open Display3D.
	 *
	 *  By default, a newly created Display3D has the scale and center transforms modified to make the scene fit nicely centered (and a bit scaled down) in the given Display3D window. The translate and rotate transforms are set to identity.
	 *
	 *  The TransformGroup instances may be reached like this:
	 *
	 *  LayerSet layer_set = Display.getFrontLayer().getParent();
	 *  Display3D d3d = Display3D.getDisplay(layer_set);
	 *  TransformGroup scaleTG = d3d.getUniverse().getGlobalScale();
	 *  TransformGroup rotationsTG = d3d.getUniverse().getGlobalRotate();
	 *  TransformGroup translateTG = d3d.getUniverse().getGlobalTranslate();
	 *  TransformGroup centerTG = d3d.getUniverse().getCenterTG();
	 *
	 *  ... and the Transform3D from each may be read out indirectly like this:
	 *
	 *  Transform3D t_scale = new Transform3D();
	 *  scaleTG.getTransform(t_scale);
	 *  ...
	 *
	 * WARNING: if your java3d setup does not support offscreen rendering, the Display3D window will be brought to the front and a screen snapshot cropped to it to perform the snapshot capture. Don't cover the Display3D window with any other windows (not even an screen saver).
	 *
	 */
	/*public ImagePlus makeSnapshot(final Transform3D scale, final Transform3D rotate, final Transform3D translate, final Transform3D center) {
		return universe.makeSnapshot(scale, rotate, translate, center);
	}*/

	/** Uses current scaling, translation and centering transforms! */
	/*public ImagePlus makeSnapshotXY() { // aka posterior
		// default view
		return universe.makeSnapshot(null, new Transform3D(), null, null);
	}*/
	/** Uses current scaling, translation and centering transforms! */
	/*public ImagePlus makeSnapshotXZ() { // aka dorsal
		Transform3D rot1 = new Transform3D();
		rot1.rotZ(-Math.PI/2);
		Transform3D rot2 = new Transform3D();
		rot2.rotX(Math.PI/2);
		rot1.mul(rot2);
		return universe.makeSnapshot(null, rot1, null, null);
	}
	*/
	/** Uses current scaling, translation and centering transforms! */
	/*
	public ImagePlus makeSnapshotYZ() { // aka lateral
		Transform3D rot = new Transform3D();
		rot.rotY(Math.PI/2);
		return universe.makeSnapshot(null, rot, null, null);
	}*/

	/*
	public ImagePlus makeSnapshotZX() { // aka frontal
		Transform3D rot = new Transform3D();
		rot.rotX(-Math.PI/2);
		return universe.makeSnapshot(null, rot, null, null);
	}
	*/

	/** Uses current scaling, translation and centering transforms! Opposite side of XZ. */
	/*
	public ImagePlus makeSnapshotXZOpp() {
		Transform3D rot1 = new Transform3D();
		rot1.rotX(-Math.PI/2); // 90 degrees clockwise
		Transform3D rot2 = new Transform3D();
		rot2.rotY(Math.PI); // 180 degrees around Y, to the other side.
		rot1.mul(rot2);
		return universe.makeSnapshot(null, rot1, null, null);
	}*/

	private class IW3DListener extends WindowAdapter {
		private Display3D d3d;
		private LayerSet ls;
		IW3DListener(Display3D d3d, LayerSet ls) {
			this.d3d = d3d;
			this.ls = ls;
		}
		public void windowClosing(WindowEvent we) {
			//Utils.log2("Display3D.windowClosing");
			d3d.executors.shutdownNow();
			/*Object ob =*/ ht_layer_sets.remove(ls);
			/*if (null != ob) {
				Utils.log2("Removed Display3D from table for LayerSet " + ls);
			}*/
		}
		public void windowClosed(WindowEvent we) {
			//Utils.log2("Display3D.windowClosed");
			ht_layer_sets.remove(ls);
		}
	}

	static private boolean check_j3d = true;
	static private boolean has_j3d_3dviewer = false;

	static private boolean hasLibs() {
		if (check_j3d) {
			check_j3d = false;
			try {
				Class.forName("javax.vecmath.Point3f");
				has_j3d_3dviewer = true;
			} catch (ClassNotFoundException cnfe) {
				Utils.log("Java 3D not installed.");
				has_j3d_3dviewer = false;
				return false;
			}
			try {
				Class.forName("ij3d.ImageWindow3D");
				has_j3d_3dviewer = true;
			} catch (ClassNotFoundException cnfe) {
				Utils.log("3D Viewer not installed.");
				has_j3d_3dviewer = false;
				return false;
			}
		}
		return has_j3d_3dviewer;
	}

	/** Get an existing Display3D for the given LayerSet, or create a new one for it (and cache it). */
	static public Display3D get(final LayerSet ls) {
		synchronized (htlock) {
			try {
				// test:
				if (!hasLibs()) return null;
				//
				Display3D d3d = ht_layer_sets.get(ls);
				if (null != d3d) return d3d;
				// Else, new:
				final boolean[] done = new boolean[]{false};
				javax.swing.SwingUtilities.invokeAndWait(new Runnable() { public void run() {
					ht_layer_sets.put(ls, new Display3D(ls));
					done[0] = true;
				}});
				// wait to avoid crashes in amd64
				// try { Thread.sleep(500); } catch (Exception e) {}
				while (!done[0]) {
					try { Thread.sleep(10); } catch (Exception e) {}
				}
				return ht_layer_sets.get(ls);
			} catch (Exception e) {
				IJError.print(e);
			}
		}
		return null;
	}

	/** Get the Display3D instance that exists for the given LayerSet, if any. */
	static public Display3D getDisplay(final LayerSet ls) {
		return ht_layer_sets.get(ls);
	}

	static public void setWaitingCursor() {
		Utils.invokeLater(new Runnable() { public void run() {
			for (Display3D d3d : ht_layer_sets.values()) {
				d3d.universe.getWindow().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
			}
		}});
	}

	static public void doneWaiting() {
		Utils.invokeLater(new Runnable() { public void run() {
			for (Display3D d3d : ht_layer_sets.values()) {
				d3d.universe.getWindow().setCursor(Cursor.getDefaultCursor());
			}
		}});
	}

	static public Future<Vector<Future<Content>>> show(ProjectThing pt) {
		return show(pt, false, -1);
	}

	static public void showAndResetView(final ProjectThing pt) {
		launchers.submit(new Runnable() {
			public void run() {
				// wait until done
				Future<Vector<Future<Content>>> fu = show(pt, true, -1);
				Vector<Future<Content>> vc;
				try {
					vc = fu.get(); // wait until done
				} catch (Exception e) {
					IJError.print(e);
					return;
				}
				for (Future<Content> fc : vc) {
					try {
						Content c = fc.get();
						if (null == c) continue;
						ArrayList<Display3D> d3ds = new ArrayList<Display3D>();
						synchronized (ht_layer_sets) {
							d3ds.addAll(ht_layer_sets.values());
						}
						/* // Disabled, it's annoying
						for (Display3D d3d : d3ds) {
							synchronized (d3d) {
								if (d3d.universe.getContents().contains(c)) {
									d3d.universe.resetView(); // reset the absolute center
									d3d.universe.adjustView(); // zoom out to bring all elements in universe within view
								}
							}
						}*/
					} catch (Exception e) {
						IJError.print(e);
					}
				}
				Utils.logAll("Reset 3D view if not within field of view!");
			}
		});
	}

	/** Scan the ProjectThing children and assign the renderable ones to an existing Display3D for their LayerSet, or open a new one. If true == wait && -1 != resample, then the method returns only when the mesh/es have been added. */
	static public Future<Vector<Future<Content>>> show(final ProjectThing pt, final boolean wait, final int resample) {
		if (null == pt) return null;

		Future<Vector<Future<Content>>> fu = launchers.submit(new Callable<Vector<Future<Content>>>() {
			public Vector<Future<Content>> call() {

		// Scan the given ProjectThing for 3D-viewable items
		// So: find arealist, pipe, ball, and profile_list types
		final HashSet<ProjectThing> hs = pt.findBasicTypeChildren();
		if (null == hs || 0 == hs.size()) {
			Utils.logAll("Node " + pt + " does not contain any 3D-displayable children");
			return null;
		}

		// Remove profile if it lives under a profile_list
		for (Iterator<ProjectThing> it = hs.iterator(); it.hasNext(); ) {
			ProjectThing pt = it.next();
			if (null != pt.getObject() && pt.getObject().getClass() == Profile.class && pt.getParent().getType().equals("profile_list")) {
				it.remove();
			}
		}

		setWaitingCursor();

		// Start new scheduler to publish/add meshes to the 3D Viewer every 5 seconds and when done.
		final Hashtable<Display3D,Vector<Content>> contents = new Hashtable<Display3D,Vector<Content>>();
		final ScheduledExecutorService updater = Executors.newScheduledThreadPool(1);
		final AtomicInteger counter = new AtomicInteger();
		updater.scheduleWithFixedDelay(new Runnable() {
			public void run() {
				// Obtain a copy of the contents queue
				HashMap<Display3D,Vector<Content>> m = new HashMap<Display3D,Vector<Content>>();
				synchronized (contents) {
					m.putAll(contents);
					contents.clear();
				}
				if (m.isEmpty()) return;
				// Add all to the corresponding Display3D
				for (Map.Entry<Display3D,Vector<Content>> e : m.entrySet()) {
					e.getKey().universe.addContentLater(e.getValue());
					counter.getAndAdd(e.getValue().size());
				}
				Utils.showStatus(new StringBuilder("Rendered ").append(counter.get()).append('/').append(hs.size()).toString());
			}
		}, 100, 4000, TimeUnit.MILLISECONDS);

		// A list of all generated Content objects
		final Vector<Future<Content>> list = new Vector<Future<Content>>();

		for (final Iterator<ProjectThing> it = hs.iterator(); it.hasNext(); ) {
			// obtain the Displayable object under the node
			final ProjectThing child = it.next();

			Object obc = child.getObject();
			final Displayable displ = obc.getClass().equals(String.class) ? null : (Displayable)obc;
			if (null != displ) {
				if (displ.getClass().equals(Profile.class)) {
					//Utils.log("Display3D can't handle Bezier profiles at the moment.");
					// handled by profile_list Thing
					continue;
				}
				if (!displ.isVisible()) {
					Utils.log("Skipping non-visible node " + displ);
					continue;
				}
			}
			// obtain the containing LayerSet
			final Display3D d3d;
			if (null != displ) d3d = Display3D.get(displ.getLayerSet());
			else if (child.getType().equals("profile_list")) {
				ArrayList<ProjectThing> al_children = child.getChildren();
				if (null == al_children || 0 == al_children.size()) continue;
				// else, get the first Profile and get its LayerSet
				d3d = Display3D.get(((Displayable)((ProjectThing)al_children.get(0)).getObject()).getLayerSet());
			} else {
				Utils.log("Don't know what to do with node " + child);
				d3d = null;
			}
			if (null == d3d) {
				Utils.log("Could not get a proper 3D display for node " + displ);
				return null; // java3D not installed most likely
			}
			
			boolean already;
			synchronized (d3d.ht_pt_meshes) {
				already = d3d.ht_pt_meshes.containsKey(child);
			}
			if (already) {
				if (child.getObject() instanceof ZDisplayable) {
					Utils.log("Updating 3D view of " + child.getObject());
				} else {
					Utils.log("Updating 3D view of " + child);
				}
			}

			list.add(d3d.executors.submit(new Callable<Content>() {
				public Content call() {
					Content c = null;
					try {
						c = d3d.createMesh(child, displ, resample).call();
						Vector<Content> vc;
						synchronized (contents) {
							vc = contents.get(d3d);
							if (null == vc) vc = new Vector<Content>();
							contents.put(d3d, vc);
						}
						vc.add(c);
					} catch (Exception e) {
						IJError.print(e);
					}
					return c;
				}
			}));

			// If it's the last one:
			if (!it.hasNext()) {
				// Add the concluding task, that waits on all and shuts down the scheduler
				d3d.executors.submit(new Runnable() {
					public void run() {
						// Wait until all are done
						for (Future<Content> c : list) {
							try {
								c.get();
							} catch (Throwable t) {
								IJError.print(t);
							}
						}
						try {
							// Shutdown scheduler and execute remaining tasks
							for (Runnable r : updater.shutdownNow()) {
								r.run();
							}
						} catch (Throwable e) {
							IJError.print(e);
						}
						// Reset cursor
						doneWaiting();
						Utils.showStatus(new StringBuilder("Done rendering ").append(counter.get()).append('/').append(hs.size()).toString());
					}
				});
			}
		}

		return list;

		}});

		if (wait && -1 != resample) {
			try {
				fu.get();
			} catch (Throwable t) {
				IJError.print(t);
			}
		}

		return fu;
	}

	static public void resetView(final LayerSet ls) {
		Display3D d3d = ht_layer_sets.get(ls);
		if (null != d3d) d3d.universe.resetView();
	}

	static public void showOrthoslices(Patch p) {
		Display3D d3d = get(p.getLayerSet());
		d3d.adjustResampling();
		//d3d.universe.resetView();
		String title = makeTitle(p) + " orthoslices";
		// remove if present
		d3d.universe.removeContent(title);
		PatchStack ps = p.makePatchStack();
		ImagePlus imp = get8BitStack(ps);
		Content ct = d3d.universe.addOrthoslice(imp, null, title, 0, new boolean[]{true, true, true}, d3d.resample);
		setTransform(ct, ps.getPatch(0));
		ct.setLocked(true); // locks the added content
	}

	static public void showVolume(Patch p) {
		Display3D d3d = get(p.getLayerSet());
		d3d.adjustResampling();
		//d3d.universe.resetView();
		String title = makeTitle(p) + " volume";
		// remove if present
		d3d.universe.removeContent(title);
		PatchStack ps = p.makePatchStack();
		ImagePlus imp = get8BitStack(ps);
		Content ct = d3d.universe.addVoltex(imp, null, title, 0, new boolean[]{true, true, true}, d3d.resample);
		setTransform(ct, ps.getPatch(0));
		ct.setLocked(true); // locks the added content
	}

	static private void setTransform(Content ct, Patch p) {
		final double[] a = new double[6];
		p.getAffineTransform().getMatrix(a);
		Calibration cal = p.getLayerSet().getCalibration();
		// a is: m00 m10 m01 m11 m02 m12
		// d expects: m01 m02 m03 m04, m11 m12 ...
		ct.applyTransform(new Transform3D(new double[]{a[0], a[2], 0, a[4] * cal.pixelWidth,
			                                       a[1], a[3], 0, a[5] * cal.pixelWidth,
					                          0,    0, 1, p.getLayer().getZ() * cal.pixelWidth,
					                          0,    0, 0, 1}));
	}

	static public void showOrthoslices(final ImagePlus imp, final String title, final int wx, final int wy, final float scale2D, final Layer first) {
		Display3D d3d = get(first.getParent());
		d3d.universe.removeContent(title);
		Content ct = d3d.universe.addOrthoslice(imp, null, title, 0, new boolean[]{true, true, true}, 1);
		Calibration cal = imp.getCalibration();
		Transform3D t = new Transform3D(new double[]{1, 0, 0, wx * cal.pixelWidth * scale2D,
													 0, 1, 0, wy * cal.pixelHeight * scale2D,
													 0, 0, scale2D, first.getZ() * cal.pixelWidth * scale2D, // not pixelDepth!
													 0, 0, 0, 1});
												// why scale2D has to be there at all reflects a horrible underlying setting of the calibration, plus of the scaling in the Display3D.
		Utils.log(t);
		ct.applyTransform(t);
		ct.setLocked(true);
	}

	/** Returns a stack suitable for the ImageJ 3D Viewer, either 8-bit gray or 8-bit color.
	 *  If the PatchStack is already of the right type, it is returned,
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
			case ImagePlus.GRAY8:
			case ImagePlus.COLOR_256:
				return ps;
			default:
				Utils.logAll("Cannot handle stacks of type: " + ps.getType());
				return null;
		}
	}

	/** Considers there is only one Display3D for each LayerSet. */
	static public void remove(ProjectThing pt) {
		if (null == pt) return;
		if (null == pt.getObject()) return;
		Object ob = pt.getObject();
		if (!(ob instanceof Displayable)) return;
		Displayable displ = (Displayable)ob;
		Display3D d3d = ht_layer_sets.get(displ.getLayerSet()); // TODO profile_list is going to fail here
		if (null == d3d) {
			// there is no Display3D showing the pt to remove
			//Utils.log2("No Display3D contains ProjectThing: " + pt);
			return;
		}
		String name;
		synchronized (d3d.ht_pt_meshes) {
			name = d3d.ht_pt_meshes.remove(pt);
		}
		if (null == name) {
			Utils.log2("No mesh contained within " + d3d + " for ProjectThing " + pt);
			return;
		}
		d3d.universe.removeContent(name);
	}


	/** Creates a mesh for the given Displayable in a separate Thread, and adds it to the universe. */
	private Future<Content> addMesh(final ProjectThing pt, final Displayable displ, final int resample) {
		return executors.submit(new Callable<Content>() {
			public Content call() {
				try {
					// 1 - Create content
					Callable<Content> c = createMesh(pt, displ, resample);
					if (null == c) return null;
					Content content = c.call();
					if (null == content) return null;
					String title = content.getName();
					// 2 - Remove from universe any content of the same title
					if (universe.contains(title)) {
						universe.removeContent(title);
					}
					// 3 - Add to universe, and wait
					universe.addContentLater(content).get();
					
					return content;

				} catch (Exception e) {
					IJError.print(e);
					return null;
				}
			}
		});
	}
	
	static private final String makeProfileListTitle(final ProjectThing pt) {
		String title;
		Object ob = pt.getParent().getTitle();
		if (null == ob || ob.equals(pt.getParent().getType())) title = pt.toString() + " #" + pt.getId(); // Project.getMeaningfulTitle can't handle profile_list properly
		else title = ob.toString() + " /[" + pt.getParent().getType() + "]/[profile_list] #" + pt.getId();
		return title;
	}

	/** Remove all basic type children contained in {@param pt} and its children, recursively.
	 * 
	 * @param pt
	 */
	static public void removeFrom3D(final ProjectThing pt) {
		final HashSet<ProjectThing> hs = pt.findBasicTypeChildren();
		if (null == hs || 0 == hs.size()) {
			Utils.logAll("Nothing to remove from 3D.");
			return;
		}
		// Ignore Profile instances ("profile_list" takes care of them)
		for (final ProjectThing child : hs) {
			if (child.getByType().equals("profile")) continue;
			// Find the LayerSet
			LayerSet lset = null;
			if (child.getType().equals("profile_list")) {
				if (!child.hasChildren()) continue;
				for (ProjectThing p : child.getChildren()) {
					if (null != p.getObject() && p.getObject() instanceof Profile) {
						lset = ((Displayable)p.getObject()).getLayerSet();
						break;
					}
				}
				if (null == lset) continue;
			} else if (child.getType().equals("profile")) {
				// Taken care of by "profile list"
				continue;
			} else {
				final Displayable d = (Displayable)child.getObject();
				if (null == d) {
					Utils.log("Null object for ProjectThing " + child);
					continue;
				}
				lset = d.getLayerSet();
			}
			if (null == lset) {
				Utils.log("No LayerSet found for " + child);
				continue;
			}
			Display3D d3d = getDisplay(lset);
			if (null == d3d) {
				Utils.log("No Display 3D found for " + child);
				continue; // no Display3D window open
			}
			String oldTitle = d3d.ht_pt_meshes.remove(child);
			if (null == oldTitle) {
				Utils.log("Could not find a title for " + child);
				continue;
			}
			Utils.log("Removed from 3D view: " + oldTitle);
			d3d.getUniverse().removeContent(oldTitle);
		}
	}


	/** Returns a function that returns a Content object.
	 *  Does NOT add the Content to the universe; it merely creates it. */
	public Callable<Content> createMesh(final ProjectThing pt, final Displayable displ, final int resample) {
		final double scale = 1.0; // OBSOLETE
		return new Callable<Content>() {
			public Content call() {
				Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
				try {

		// the list 'triangles' is really a list of Point3f, which define a triangle every 3 consecutive points. (TODO most likely Bene Schmid got it wrong: I don't think there's any need to have the points duplicated if they overlap in space but belong to separate triangles.)
		final List<Point3f> triangles;
		//boolean no_culling_ = false;

		final Class<?> c;
		final boolean line_mesh;
		final int line_mesh_mode;
		if (null == displ) {
			c = null;
			line_mesh = false;
			line_mesh_mode = Integer.MAX_VALUE;
		} else {
			c = displ.getClass();
			line_mesh = Tree.class.isAssignableFrom(c) || Polyline.class == c;
			if (Tree.class.isAssignableFrom(c)) line_mesh_mode = CustomLineMesh.PAIRWISE;
			else if (Polyline.class == c) line_mesh_mode = CustomLineMesh.CONTINUOUS;
			else line_mesh_mode = Integer.MAX_VALUE; // disabled
		}

		List<Point3f> extra_triangles = null;
		List<Color3f> triangle_colors = null,
					  extra_triangle_colors = null;

		int rs = resample;
		if (displ instanceof AreaContainer) {
			if (-1 == resample) rs = Display3D.this.resample = adjustResampling(); // will adjust this.resample, and return it (even if it's a default value)
			else rs = Display3D.this.resample;
		}
		if (AreaList.class == c) {
			triangles = ((AreaList)displ).generateTriangles(scale, rs);
			//triangles = removeNonManifold(triangles);
		} else if (Ball.class == c) {
			double[][][] globe = Ball.generateGlobe(12, 12);
			triangles = ((Ball)displ).generateTriangles(scale, globe);
		} else if (displ instanceof Line3D) {
			// Pipe and Polyline
			// adjustResampling();  // fails horribly, needs first to correct mesh-generation code
			triangles = ((Line3D)displ).generateTriangles(scale, 12, 1 /*Display3D.this.resample*/);
		} else if (displ instanceof Tree<?>) {
			// A 3D wire skeleton, using CustomLineMesh
			final Tree.MeshData skeleton = ((Tree<?>)displ).generateSkeleton(scale, 12, 1);
			triangles = skeleton.verts;
			triangle_colors = skeleton.colors;
			if (displ instanceof Treeline) {
				final Tree.MeshData tube = ((Treeline)displ).generateMesh(scale, 12);
				extra_triangles = tube.verts;
				extra_triangle_colors = tube.colors;
			} else if (displ instanceof AreaTree) {
				final Tree.MeshData mesh = ((AreaTree)displ).generateMesh(scale, rs);
				extra_triangles = mesh.verts;
				extra_triangle_colors = mesh.colors;
			}
			if (null != extra_triangles && extra_triangles.isEmpty()) extra_triangles = null; // avoid issues with MultiMesh
		} else if (Connector.class == c) {
			final Tree.MeshData octopus = ((Connector)displ).generateMesh(scale, 12);
			triangles = octopus.verts;
			triangle_colors = octopus.colors;
		} else if (null == displ && pt.getType().equals("profile_list")) {
			triangles = Profile.generateTriangles(pt, scale);
			//no_culling_ = true;
		} else {
			Utils.log("Unrecognized type for 3D mesh generation: " + (null != displ ? displ.getClass() : null) + " : " + displ);
			triangles = null;
		}
		// safety checks
		if (null == triangles) {
			Utils.log("Some error ocurred: can't create triangles for " + displ);
			return null;
		}
		if (0 == triangles.size()) {
			Utils.log2("Skipping empty mesh for " + displ.getTitle());
			return null;
		}
		if (!line_mesh && 0 != triangles.size() % 3) {
			Utils.log2("Skipping non-multiple-of-3 vertices list generated for " + displ.getTitle());
			return null;
		}

		final Color color;
		final float alpha;
		final String title;
		if (null != displ) {
			color = displ.getColor();
			alpha = displ.getAlpha();
			title = makeTitle(displ);
		} else if (pt.getType().equals("profile_list")) {
			// for profile_list: get from the first (what a kludge; there should be a ZDisplayable ProfileList object)
			Object obp = ((ProjectThing)pt.getChildren().get(0)).getObject();
			if (null == obp) return null;
			Displayable di = (Displayable)obp;
			color = di.getColor();
			alpha = di.getAlpha();
			title = makeProfileListTitle(pt);
		} else {
			title = pt.toString() + " #" + pt.getId();
			color = null;
			alpha = 1.0f;
		}

		// Why for all? Above no_culling_ is set to true or false, depending upon type. --> Because with transparencies it looks proper and better when no_culling is true.
		final boolean no_culling = true; // for ALL

		Content ct = null;

		try {
			Color3f c3 = new Color3f(color);

			// If it exists, remove and add as new:
			universe.removeContent(title);

			final CustomMesh cm;

			if (line_mesh) {
				//ct = universe.createContent(new CustomLineMesh(triangles, line_mesh_mode, c3, 0), title);
				cm = new CustomLineMesh(triangles, line_mesh_mode, c3, 0);
			} else if (no_culling) {
				// create a mesh with the same color and zero transparency (that is, full opacity)
				CustomTriangleMesh mesh = new CustomTriangleMesh(triangles, c3, 0);
				// Set mesh properties for double-sided triangles
				PolygonAttributes pa = mesh.getAppearance().getPolygonAttributes();
				pa.setCullFace(PolygonAttributes.CULL_NONE);
				pa.setBackFaceNormalFlip(true);
				mesh.setColor(c3);
				// After setting properties, add to the viewer
				//ct = universe.createContent(mesh, title);
				cm = mesh;
			} else {
				//ct = universe.createContent(new CustomTriangleMesh(triangles, c3, 0), title);
				cm = new CustomTriangleMesh(triangles, c3, 0);
			}

			if (null != triangle_colors) cm.setColor(triangle_colors);

			//if (null == cm) return null;

			if (null == extra_triangles || 0 == extra_triangles.size()) {
				ct = universe.createContent(cm, title);
			} else {
				final CustomTriangleMesh extra = new CustomTriangleMesh(extra_triangles, c3, 0);
				if (null != extra_triangle_colors) {
					// Set mesh properties for double-sided triangles
					PolygonAttributes pa = extra.getAppearance().getPolygonAttributes();
					pa.setCullFace(PolygonAttributes.CULL_NONE);
					pa.setBackFaceNormalFlip(true);
					extra.setColor(extra_triangle_colors);
				}
				ct = universe.createContent(new CustomMultiMesh(Arrays.asList(new CustomMesh[]{cm, extra})), title);
			}

			// Set general content properties
			ct.setTransparency(1f - alpha);
			// Default is unlocked (editable) transformation; set it to locked:
			ct.setLocked(true);

			// register mesh title
			synchronized (ht_pt_meshes) {
				ht_pt_meshes.put(pt, ct.getName());
			}

		} catch (Throwable e) {
			Utils.logAll("Mesh generation failed for \"" + title + "\"  from " + pt);
			IJError.print(e);
			e.printStackTrace();
		}

		Utils.log2(pt.toString() + " n points: " + triangles.size());

		return ct;

				} catch (Exception e) {
					IJError.print(e);
					return null;
				}

		}};
	}

	static public class VectorStringContent {
		VectorString3D vs;
		String title;
		Color color;
		double[] widths;
		float alpha;
		public VectorStringContent(VectorString3D vs, String title, Color color, double[] widths, float alpha){
			this.vs = vs;
			this.title = title;
			this.color = color;
			this.widths = widths;
			this.alpha = alpha;
		}
		public Content asContent(Display3D d3d) {
			double[] wi = widths;
			if (null == widths) {
				wi = new double[vs.getPoints(0).length];
				Arrays.fill(wi, 2.0);
			} else if (widths.length != vs.length()) {
				Utils.log("ERROR: widths.length != VectorString3D.length()");
				return null;
			}
			float transp = 1 - alpha;
			if (transp < 0) transp = 0;
			if (transp > 1) transp = 1;
			if (1 == transp) {
				Utils.log("WARNING: adding a 3D object fully transparent.");
			}
			List<Point3f> triangles = Pipe.generateTriangles(Pipe.makeTube(vs.getPoints(0), vs.getPoints(1), vs.getPoints(2), wi, 1, 12, null), d3d.scale);
			Content ct = d3d.universe.createContent(new CustomTriangleMesh(triangles, new Color3f(color), 0), title);
			ct.setTransparency(transp);
			ct.setLocked(true);
			return ct;
		}
	}

	/** Creates a mesh from the given VectorString3D, which is unbound to any existing Pipe. */
	static public Future<Collection<Future<Content>>> addMesh(final LayerSet ref_ls, final VectorString3D vs, final String title, final Color color) {
		return addMesh(ref_ls, vs, title, color, null, 1.0f);
	}

	/** Creates a mesh from the given VectorString3D, which is unbound to any existing Pipe. */
	static public Future<Collection<Future<Content>>> addMesh(final LayerSet ref_ls, final VectorString3D vs, final String title, final Color color, final double[] widths, final float alpha) {
		List<Content> col = new ArrayList<Content>();
		Display3D d3d = Display3D.get(ref_ls);
		col.add(new VectorStringContent(vs, title, color, widths, alpha).asContent(d3d));
		return d3d.addContent(col);
	}

	static public Future<Collection<Future<Content>>> show(final LayerSet ref_ls, final Collection<Content> col) {
		Display3D d3d = get(ref_ls);
		return d3d.addContent(col);
	}

	public Future<Collection<Future<Content>>> addContent(final Collection<Content> col) {

		final FutureTask<Collection<Future<Content>>> fu = new FutureTask<Collection<Future<Content>>>(new Callable<Collection<Future<Content>>>() {
			public Collection<Future<Content>> call() {
				Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
				try {
					return universe.addContentLater(col);
				} catch (Throwable e) {
					IJError.print(e);
					return null;
				}
		}});

		launchers.submit(new Runnable() { public void run() {
			executors.submit(fu);
		}});

		return fu;
	}

	public Future<Content> addContent(final Content c) {
		final FutureTask<Content> fu = new FutureTask<Content>(new Callable<Content>() {
			public Content call() {
				Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
				try {
					return universe.addContentLater(c).get();
				} catch (Throwable e) {
					IJError.print(e);
					return null;
				}
			}
		});

		launchers.submit(new Runnable() { public void run() {
			executors.submit(fu);
		}});

		return fu;
	}

	static public final int estimateResamplingFactor(final LayerSet ls, final double width, final double height) {
		final int max_dimension = ls.getPixelsMaxDimension();
		return (int)(DEFAULT_RESAMPLE / (Math.max(width, height) > max_dimension ?
			  max_dimension / Math.max(width, height)
			: 1));
	}

	/** Estimate a scaling factor, to be used as a multiplier of the suggested default resampling. */
	private final int estimateResamplingFactor() {
		return estimateResamplingFactor(layer_set, width, height);
	}

	// This method has the exclusivity in adjusting the resampling value, and it also returns it.
	synchronized private final int adjustResampling() {
		if (resample > 0) return resample;
		final GenericDialog gd = new GenericDialog("Resample");
		final int default_resample = estimateResamplingFactor();
		gd.addSlider("Resample: ", 1, Math.max(default_resample, 100), -1 != resample ? resample : default_resample);
		gd.showDialog();
		if (gd.wasCanceled()) {
			resample = -1 != resample ? resample : default_resample; // current or default value
			return resample;
		}
		resample = ((java.awt.Scrollbar)gd.getSliders().get(0)).getValue();
		return resample;
	}

	/** Checks if there is any Display3D instance currently showing the given Displayable. */
	static public boolean isDisplayed(final Displayable d) {
		if (null == d) return false;
		final String title = makeTitle(d);
		for (Display3D d3d : ht_layer_sets.values()) {
			if (null != d3d.universe.getContent(title)) return true;
		}
		if (d.getClass() == Profile.class) {
			if (null != getProfileContent(d)) return true;
		}
		return false;
	}

	/** Checks if the given Displayable is a Profile, and tries to find a possible Content object in the Image3DUniverse of its LayerSet according to the title as created from its profile_list ProjectThing. */
	static public Content getProfileContent(final Displayable d) {
		if (null == d) return null;
		if (d.getClass() != Profile.class) return null;
		Display3D d3d = get(d.getLayer().getParent());
		if (null == d3d) return null;
		ProjectThing pt = d.getProject().findProjectThing(d);
		if (null == pt) return null;
		pt = (ProjectThing) pt.getParent();
		return d3d.universe.getContent(new StringBuilder(pt.toString()).append(" #").append(pt.getId()).toString());
	}

	static public Future<Boolean> setColor(final Displayable d, final Color color) {
		final Display3D d3d = getDisplay(d.getLayer().getParent());
		if (null == d3d) return null; // no 3D displays open
		return d3d.executors.submit(new Callable<Boolean>() { public Boolean call() {
			Content content = d3d.universe.getContent(makeTitle(d));
			if (null == content) content = getProfileContent(d);
			if (null != content) {
				content.setColor(new Color3f(color));
				return true;
			}
			return false;
		}});
	}

	static public Future<Boolean> setTransparency(final Displayable d, final float alpha) {
		if (null == d) return null;
		Layer layer = d.getLayer();
		if (null == layer) return null; // some objects have no layer, such as the parent LayerSet.
		final Display3D d3d = ht_layer_sets.get(layer.getParent());
		if (null == d3d) return null;
		return d3d.executors.submit(new Callable<Boolean>() { public Boolean call() {
			String title = makeTitle(d);
			Content content = d3d.universe.getContent(title);
			if (null == content) content = getProfileContent(d);
			if (null != content) content.setTransparency(1 - alpha);
			else if (null == content && d.getClass().equals(Patch.class)) {
				Patch pa = (Patch)d;
				if (pa.isStack()) {
					title = pa.getProject().getLoader().getFileName(pa);
					for (Display3D dd : ht_layer_sets.values()) {
						for (Iterator<?> cit = dd.universe.getContents().iterator(); cit.hasNext(); ) {
							Content c = (Content)cit.next();
							if (c.getName().startsWith(title)) {
								c.setTransparency(1 - alpha);
								// no break, since there could be a volume and an orthoslice
							}
						}
					}
				}
			}
			return true;
		}});
	}

	static public String makeTitle(final Displayable d) {
		return d.getProject().getMeaningfulTitle(d) + " #" + d.getId();
	}
	static public String makeTitle(final Patch p) {
		return new File(p.getProject().getLoader().getAbsolutePath(p)).getName()
		       + " #" + p.getProject().getLoader().getNextId();
	}

	/** Remake the mesh for the Displayable in a separate Thread, if it's included in a Display3D
	 *  (otherwise returns null). */
	static public Future<Content> update(final Displayable d) {
		Layer layer = d.getLayer();
		if (null == layer) return null; // some objects have no layer, such as the parent LayerSet.
		Display3D d3d = ht_layer_sets.get(layer.getParent());
		if (null == d3d) return null;
		return d3d.addMesh(d.getProject().findProjectThing(d), d, d3d.resample);
	}

	/*
	static public final double computeTriangleArea() {
		return 0.5 *  Math.sqrt(Math.pow(xA*yB + xB*yC + xC*yA, 2) +
					Math.pow(yA*zB + yB*zC + yC*zA, 2) +
					Math.pow(zA*xB + zB*xC + zC*xA, 2));
	}
	*/

	static public final boolean contains(final LayerSet ls, final String title) {
		final Display3D d3d = getDisplay(ls);
		if (null == d3d) return false;
		return null != d3d.universe.getContent(title);
	}

	static public void destroy() {
		launchers.shutdownNow();
	}

	static public void init() {
		if (launchers.isShutdown()) {
			launchers = Utils.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), "Display3D-launchers");
		}
	}

	/** Creates a calibrated sphere to represent a point at LayerSet pixel coordinates wx, wy, wz, with radius wr.*/
	public List<Point3f> createFatPoint(final double wx, final double wy, final double wz, final double wr, final Calibration cal) {
		final double[][][] globe = Ball.generateGlobe(12, 12);
		final int sign = cal.pixelDepth < 0 ? -1 : 1;
		for (int z=0; z<globe.length; z++) {
			for (int k=0; k<globe[0].length; k++) {
				globe[z][k][0] = (globe[z][k][0] * wr + wx) * scale * cal.pixelWidth;
				globe[z][k][1] = (globe[z][k][1] * wr + wy) * scale * cal.pixelHeight;
				globe[z][k][2] = (globe[z][k][2] * wr + wz) * scale * cal.pixelWidth * sign; // not pixelDepth, see day notes 20080227. Because pixelDepth is in microns/px, not in px/microns, and the z coord here is taken from the z of the layer, which is in pixels.
			}
		}
		final ArrayList<Point3f> list = new ArrayList<Point3f>();
		// create triangular faces and add them to the list
		for (int z=0; z<globe.length-1; z++) { // the parallels
			for (int k=0; k<globe[0].length -1; k++) { // meridian points
				// half quadrant (a triangle)
				list.add(new Point3f((float)globe[z][k][0], (float)globe[z][k][1], (float)globe[z][k][2]));
				list.add(new Point3f((float)globe[z+1][k+1][0], (float)globe[z+1][k+1][1], (float)globe[z+1][k+1][2]));
				list.add(new Point3f((float)globe[z+1][k][0], (float)globe[z+1][k][1], (float)globe[z+1][k][2]));
				// the other half quadrant
				list.add(new Point3f((float)globe[z][k][0], (float)globe[z][k][1], (float)globe[z][k][2]));
				list.add(new Point3f((float)globe[z][k+1][0], (float)globe[z][k+1][1], (float)globe[z][k+1][2]));
				list.add(new Point3f((float)globe[z+1][k+1][0], (float)globe[z+1][k+1][1], (float)globe[z+1][k+1][2]));
			}
		}
		return list;
	}

	/** Expects uncalibrated wx,wy,wz, (i.e. pixel values), to be calibrated by @param ls calibration. */
	static public final Future<Content> addFatPoint(final String title, final LayerSet ls, final double wx, final double wy, final double wz, final double wr, final Color color) {
		Display3D d3d = Display3D.get(ls);
		d3d.universe.removeContent(title);
		Content ct = d3d.universe.createContent(new CustomTriangleMesh(d3d.createFatPoint(wx, wy, wz, wr, ls.getCalibrationCopy()), new Color3f(color), 0), title);
		ct.setLocked(true);
		return d3d.addContent(ct);
	}

}
