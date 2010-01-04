package ini.trakem2.display;

import ini.trakem2.Project;
import java.util.HashMap;
import java.util.Map;
import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedList;
import ij.measure.Calibration;
import javax.vecmath.Point3f;

public class Treeline extends Tree {

	static private float last_radius = -1;

	public Treeline(Project project, String title) {
		super(project, title);
		addToDatabase();
	}

	/** Reconstruct from XML. */
	public Treeline(final Project project, final long id, final HashMap ht_attr, final HashMap ht_links) {
		super(project, id, ht_attr, ht_links);
	}

	/** For cloning purposes, does not call addToDatabase() */
	public Treeline(final Project project, final long id, final String title, final double width, final double height, final float alpha, final boolean visible, final Color color, final boolean locked, final AffineTransform at) {
		super(project, id, title, width, height, alpha, visible, color, locked, at);
	}

	public Tree newInstance() {
		return new Treeline(project, project.getLoader().getNextId(), title, width, height, alpha, visible, color, locked, at);
	}

	public Node newNode(float lx, float ly, Layer la) {
		return new RadiusNode(lx, ly, la, last_radius);
	}

	public Treeline clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		Treeline tline =  new Treeline(pr, nid, title, width, height, alpha, visible, color, locked, at);
		tline.root = this.root.clone();
		tline.addToDatabase();
		return tline;
	}

	@Override
	public void mousePressed(MouseEvent me, int x_p, int y_p, double mag) {
		if (-1 == last_radius) {
			last_radius = 10 / (float)mag;
		}
		super.mousePressed(me, x_p, y_p, mag);
	}

	static public final class RadiusNode extends Node<Float> {
		private float r;

		public RadiusNode(final float lx, final float ly, final Layer la) {
			this(lx, ly, la, 0);
		}
		public RadiusNode(final float lx, final float ly, final Layer la, final float radius) {
			super(lx, ly, la);
			this.r = radius;
		}
		/** To reconstruct from XML, without a layer. */
		public RadiusNode(final HashMap attr) {
			super(attr);
			this.r = Float.parseFloat((String)attr.get("r"));
		}

		public final Node newInstance(final float lx, final float ly, final Layer layer) {
			return new RadiusNode(lx, ly, layer, 0);
		}

		public final boolean setData(Float radius) {
			this.r = radius;
			return true;
		}
		public final Float getData() { return this.r; }
	}

	/** Testing for performance, 100 iterations:
	 * A: 3307  (current, with clearing of table on the fly)
	 * B: 4613  (without clearing table)
	 * C: 4012  (without point caching)
	 *
	 * Although in short runs (10 iterations) A can get very bad:
	 * (first run of 10)
	 * A: 664
	 * B: 611
	 * C: 196
	 * (second run of 10)
	 * A: 286
	 * B: 314
	 * C: 513  <-- gets worse !?
	 *
	 * Differences are not so huge in any case.
	 */
	static final public void testMeshGenerationPerformance(int n_iterations) {
		// test 3D mesh generation

		Layer la = Display.getFrontLayer();
		java.util.Random rnd = new java.util.Random(67779);
		Node root = new RadiusNode(rnd.nextFloat(), rnd.nextFloat(), la);
		Node parent = root;
		for (int i=0; i<10000; i++) {
			Node child = new RadiusNode(rnd.nextFloat(), rnd.nextFloat(), la);
			parent.add(child, Node.MAX_EDGE_CONFIDENCE);
			if (0 == i % 100) {
				// add a branch of 100 nodes
				Node pa = parent;
				for (int k = 0; k<100; k++) {
					Node ch = new RadiusNode(rnd.nextFloat(), rnd.nextFloat(), la);
					pa.add(ch, Node.MAX_EDGE_CONFIDENCE);
					pa = ch;
				}
			}
			parent = child;
		}

		final AffineTransform at = new AffineTransform(1, 0, 0, 1, 67, 134);

		final ArrayList list = new ArrayList();

		final LinkedList<Node> todo = new LinkedList<Node>();

		final float scale = 0.345f;
		final Calibration cal = la.getParent().getCalibration();
		final float pixelWidthScaled = (float) cal.pixelWidth * scale;
		final float pixelHeightScaled = (float) cal.pixelHeight * scale;
		final int sign = cal.pixelDepth < 0 ? -1 : 1;
		final Map<Node,Point3f> points = new HashMap<Node,Point3f>();

		// A few performance tests are needed:
		// 1 - if the map caching of points helps or recomputing every time is cheaper than lookup
		// 2 - if removing no-longer-needed points from the map helps lookup or overall slows down

		long t0 = System.currentTimeMillis();
		for (int i=0; i<n_iterations; i++) {
			// A -- current method
			points.clear();
			todo.clear();
			todo.add(root);
			list.clear();
			final float[] fps = new float[2];
			
			boolean go = true;
			while (go) {
				final Node node = todo.removeFirst();
				// Add children to todo list if any
				if (null != node.children) {
					for (final Node nd : node.children) todo.add(nd);
				}
				go = !todo.isEmpty();
				// Get node's 3D coordinate
				Point3f p = points.get(node);
				if (null == p) {
					fps[0] = node.x;
					fps[1] = node.y;
					at.transform(fps, 0, fps, 0, 1);
					p = new Point3f(fps[0] * pixelWidthScaled,
							fps[1] * pixelHeightScaled,
							(float)node.la.getZ() * pixelWidthScaled * sign);
					points.put(node, p);
				}
				if (null != node.parent) {
					// Create a line to the parent
					list.add(points.get(node.parent));
					list.add(p);
					if (go && node.parent != todo.getFirst().parent) {
						// node.parent point no longer needed (last child just processed)
						points.remove(node.parent);
					}
				}
			}
		}
		System.out.println("A: " + (System.currentTimeMillis() - t0));


		t0 = System.currentTimeMillis();
		for (int i=0; i<n_iterations; i++) {

			points.clear();
			todo.clear();
			todo.add(root);
			list.clear();
			final float[] fps = new float[2];

			// Simpler method, not clearing no-longer-used nodes from map
			while (!todo.isEmpty()) {
				final Node node = todo.removeFirst();
				// Add children to todo list if any
				if (null != node.children) {
					for (final Node nd : node.children) todo.add(nd);
				}
				// Get node's 3D coordinate
				Point3f p = points.get(node);
				if (null == p) {
					fps[0] = node.x;
					fps[1] = node.y;
					at.transform(fps, 0, fps, 0, 1);
					p = new Point3f(fps[0] * pixelWidthScaled,
							fps[1] * pixelHeightScaled,
							(float)node.la.getZ() * pixelWidthScaled * sign);
					points.put(node, p);
				}
				if (null != node.parent) {
					// Create a line to the parent
					list.add(points.get(node.parent));
					list.add(p);
				}
			}
		}
		System.out.println("B: " + (System.currentTimeMillis() - t0));

		t0 = System.currentTimeMillis();
		for (int i=0; i<n_iterations; i++) {

			todo.clear();
			todo.add(root);
			list.clear();

			// Simplest method: no caching in a map
			final float[] fp = new float[4];
			while (!todo.isEmpty()) {
				final Node node = todo.removeFirst();
				// Add children to todo list if any
				if (null != node.children) {
					for (final Node nd : node.children) todo.add(nd);
				}
				if (null != node.parent) {
					// Create a line to the parent
					fp[0] = node.x;
					fp[1] = node.y;
					fp[2] = node.parent.x;
					fp[3] = node.parent.y;
					at.transform(fp, 0, fp, 0, 2);
					list.add(new Point3f(fp[2] * pixelWidthScaled,
							     fp[3] * pixelHeightScaled,
							     (float)node.parent.la.getZ() * pixelWidthScaled * sign));
					list.add(new Point3f(fp[0] * pixelWidthScaled,
							     fp[1] * pixelHeightScaled,
							     (float)node.la.getZ() * pixelWidthScaled * sign));
				}
			}
		}
		System.out.println("C: " + (System.currentTimeMillis() - t0));
	}

}
