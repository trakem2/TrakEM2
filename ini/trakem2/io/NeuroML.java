package ini.trakem2.io;

import ij.measure.Calibration;
import ini.trakem2.display.AreaTree;
import ini.trakem2.display.Node;
import ini.trakem2.display.Tree;
import ini.trakem2.display.Treeline;
import ini.trakem2.utils.Utils;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

public final class NeuroML {
	
	/** Given a branch or end node, gather the list of nodes all the way
	 * up to the previous branch node or root (not included).
	 * The list has be as the first node. */
	static private final <T> List<Node<T>> cable(final Node<T> be) {
		final ArrayList<Node<T>> slab = new ArrayList<Node<T>>();
		slab.add(be);
		// Collect nodes up to a node (not included)
		//   that must have a parent (so not the root)
		//   and more than one child.
		Node<T> p1 = be.getParent();
		Node<T> p2 = null == p1 ? null : p1.getParent();
		while (p2 != null && 1 == p1.getChildrenCount()) {
			slab.add(p1);
			p1 = p2;
			p2 = p2.getParent();
		}
		return slab;
	}
	
	static private final void writeCellHeader(final Writer w, final Tree<?> t) throws IOException {
		w.write("<cell name=\""); w.write(Long.toString(t.getId())); w.write("\">\n");
		w.write(" <meta:notes>");
		w.write(t.getProject().getMeaningfulTitle(t));
		final String annotation = t.getAnnotation();
		if (null != annotation) {
			w.write("\n");
			w.write(t.getAnnotation());
		}
		w.write(" </meta:notes>\n");
		w.write(" <meta:properties>\n");
		w.write("  <meta:property tag=\"Neuron type\" value=\"manually reconstructed\" />\n");
		w.write(" </meta:properties>\n");
		w.write(" <segments>\n");
	}

	/** Transform in 2d the point with the given affine that combines the Tree affine and the calibration,
	 * along with its z and radius.
	 * 
	 * Then the data is stored into fp as:
	 * fp[0] = x
	 * fp[1] = y
	 * fp[2] = z
	 * fp[3] = radius
	 */
	static private final void toPoint(final Node<Float> nd, final float[] fp, final AffineTransform aff, final double zScale) {
		// 0,1: the point
		fp[0] = nd.getX();
		fp[1] = nd.getY();
		// 2,3: the point with x displaced by the radius
		fp[2] = fp[0] + nd.getData();
		fp[3] = fp[1];
		//
		aff.transform(fp, 0, fp, 0, 2);
		// Compute transformed radius
		fp[3] = (float) Math.sqrt(Math.pow(fp[2] - fp[0], 2) + Math.pow(fp[3] - fp[1], 2));
		// Set Z
		fp[2] = (float)(nd.getLayer().getZ() * zScale);
	}
	
	static private final void writeSomaSegment(final Writer w, final float[] root) throws IOException {
		w.write("  <segment id=\"0\" name=\"0\" cable=\"0\">\n");
		final String sx = Float.toString(root[0]),
		             sy = Float.toString(root[1]),
		             sz = Float.toString(root[2]),
		             sd = Float.toString(Math.max(1, 2 * root[3])); // it's a radius, and at least the diameter should be 1
		w.write("   <proximal x=\""); w.write(sx);
		w.write("\" y=\""); w.write(sy);
		w.write("\" z=\""); w.write(sz);
		w.write("\" diameter=\""); w.write(sd);
		w.write("\"/>\n   <distal x=\""); w.write(sx);
		w.write("\" y=\""); w.write(sy);
		w.write("\" z=\""); w.write(sz);
		w.write("\" diameter=\""); w.write(sd);
		w.write("\"/>\n  </segment>\n");
	}
	
	static private final void writeCableSegment(final Writer w,
			final float[] seg, final long segId,
			final long parentId, final float[] parentCoords,
			final String sCableId) throws IOException
	{
		final String sid = Long.toString(segId);
		w.write("  <segment id=\""); w.write(sid); w.write("\" name=\""); w.write(sid);
		w.write("\" parent=\""); w.write(Long.toString(parentId));
		w.write("\" cable=\""); w.write(sCableId);
		w.write("\">\n");
		if (null != parentCoords) {
			w.write("   <proximal x=\""); w.write(Float.toString(parentCoords[0]));
			w.write("\" y=\""); w.write(Float.toString(parentCoords[1]));
			w.write("\" z=\""); w.write(Float.toString(parentCoords[2]));
			w.write("\" diameter=\""); w.write(Float.toString(Math.max(1, parentCoords[3])));
			w.write("\"/>\n");
		}
		w.write("   <distal x=\""); w.write(Float.toString(seg[0]));
		w.write("\" y=\""); w.write(Float.toString(seg[1]));
		w.write("\" z=\""); w.write(Float.toString(seg[2]));
		w.write("\" diameter=\""); w.write(Float.toString(Math.max(1, seg[3]))); // at least 1
		w.write("\"/>\n  </segment>\n");
	}

	static private final class Synapse {
		final long srcCellId, srcSegmentId, tgtCellId, tgtSegmentId;
		Synapse(final long srcCellId, final long srcSegmentId,
				final long tgtCellId, final long tgtSegmentId) {
			this.srcCellId = srcCellId;
			this.srcSegmentId = srcSegmentId;
			this.tgtCellId = tgtCellId;
			this.tgtSegmentId = tgtSegmentId;
		}
	}

	static private final String units(final Tree<?> d) {
		final Calibration cal = d.getLayerSet().getCalibration();
		final String unit = cal.getUnit().toLowerCase();
		if (unit.equals("nm") || unit.matches("^nanometers*$")) return "nanometers";
		if (unit.equals("µm") || unit.matches("^micrometers*$")) return "micrometers";
		return cal.getUnits();
	}
	
	static public final void exportMorphML(final Collection<Tree<?>> trees, final Writer w) throws Exception {
		exportMorphML(new HashSet<Tree<?>>(trees), w);
	}

	static public final void exportMorphML(final Set<Tree<?>> trees, final Writer w) throws Exception
	{
		if (trees.isEmpty()) return;
		
		// Header
		w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		w.write("<morphml xmlns=\"http://morphml.org/morphml/schema\"\n");
		w.write("  xmlns:meta=\"http://morphml.org/metadata/schema\"\n");
		w.write("  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
		w.write("  xsi:schemaLocation=\"http://morphml.org/morphml/schema http://www.neuroml.org/NeuroMLValidator/NeuroMLFiles/Schemata/v1.8.1/Level1/MorphML_v1.8.1.xsd\"\n");
		w.write("  length_units=\"micrometers\">\n<cells>\n");
		
		final Set<Synapse> synapses = new HashSet<Synapse>();
		

		// Scale units to micrometers
		final double scale;
		final Calibration cal = trees.iterator().next().getLayerSet().getCalibration();
		final String unit = cal.getUnit().trim().toLowerCase();
		if (unit.equals("nanometer") || unit.equals("nm")) scale = 0.001;
		else if (unit.equals("micrometer") || unit.equals("µm") || unit.equals("um")) scale = 1;
		else if (unit.equals("milimeter") || unit.equals("mm")) scale = 1000;
		else if (unit.equals("meter") || unit.equals("m")) scale = 1000000;
		else {
			scale = 1;
			Utils.logAll("UNKNOWN unit '" + unit + "' -- using scale of 1.");
		}
		
		final AffineTransform scale2d = new AffineTransform(cal.pixelWidth * scale, 0, 0, cal.pixelHeight * scale, 0, 0);
		final double zScale = cal.pixelWidth * scale; // not pixelDepth
		
		// Each Tree is a cell
		for (final Tree<?> t : trees)
		{
			if (null == t.getRoot()) continue;
	
			exportMorphMLCell(t, w, trees, synapses, scale2d, zScale);
		}
		
		// TODO write a project group with all the synapses among the members of the trees set.

		w.write("</cells>\n</morphml>\n");
	}

	/** Without headers, just the cell block for a single AreaTree.
	 *  Works by duplicating the AreaTree as a Treeline. */
	static private final void exportMorphMLCell(final Tree<?> t, final Writer w,
			Set<Tree<?>> trees, Set<Synapse> synapses,
			final AffineTransform scale2d, final double zScale) throws Exception
	{
		if (t instanceof Treeline) {
			exportMorphMLCell((Treeline)t, w, trees, synapses, scale2d, zScale);
		} else if (t instanceof AreaTree) {
			exportMorphMLCell(Tree.copyAs((AreaTree)t, Treeline.class, Treeline.RadiusNode.class), w, trees, synapses, scale2d, zScale);
		}
	}

	/** Without headers, just the cell block for a single Treeline. */
	static private final void exportMorphMLCell(final Treeline t, final Writer w,
			Set<Tree<?>> trees, Set<Synapse> synapses,
			final AffineTransform scale2d, final double zScale) throws IOException
	{
		
		// TODO collect synapses into the set
		
		
		final float[] fp = new float[4]; // x, y, z, r

		// Prepare transform
		final AffineTransform aff = new AffineTransform(t.getAffineTransform());
		aff.preConcatenate(scale2d);

		writeCellHeader(w, t);

		// Map of Node vs id of the node
		// These ids are used to express parent-child relationships between segments
		final HashMap<Node<Float>,Long> nodeIds = new HashMap<Node<Float>,Long>();

		// Map of coords for branch or end nodes
		// so that the start of a cable can write the proximal coords
		final HashMap<Node<Float>,float[]> nodeCoords = new HashMap<Node<Float>,float[]>();

		// Root gets ID of 0:
		long nextId = 0;
		final Node<Float> root = t.getRoot();

		toPoint(root, fp, aff, zScale);
		writeSomaSegment(w, fp); // a dummy segment that has no length

		// Cable ids
		long cableId = 0;

		// Prepare
		cableId += 1;
		nodeIds.put(root, nextId++);
		nodeCoords.put(root, fp.clone());

		// All cables that come out of the Soma (the root) require a special tag:
		final HashSet<Long> somaCables = new HashSet<Long>();

		// Iterate all cables (all slabs)
		for (final Node<Float> node : t.getRoot().getBranchAndEndNodes()) {
			// Gather the list of nodes all the way up to the previous branch node or root,
			// that last one not included.
			final List<Node<Float>> slab = cable(node);
			final String sCableId = Long.toString(cableId);
			// The id of the parent already exists, given that the Collection
			// is iterated depth-first from the root.
			final Node<Float> parent = slab.get(slab.size()-1).getParent();
			long parentId = nodeIds.get(parent);
			// Use the parent coords for the proximal coords of the first segment of the cable
			float[] parentCoords = nodeCoords.get(parent);
			// Is it a cable coming out of the root node (the soma) ?
			if (0 == parentId) somaCables.add(cableId);
			// For every node starting from the closest to the root (the last),
			// write a segment of the cable
			for (final ListIterator<Node<Float>> it = slab.listIterator(slab.size()); it.hasPrevious(); ) {
				// Assign an id to the node of the slab
				final Node<Float> seg = it.previous();
				// Write the segment
				toPoint(seg, fp, aff, zScale);
				writeCableSegment(w, fp, nextId, parentId, parentCoords, sCableId);
				// Prepare next segment in the cable
				parentId = nextId;
				nextId += 1;
				parentCoords = null; // is used only for the first node
			}
			// Record the branch node, to be used for filling in "distal" fields
			if (node.getChildrenCount() > 1) {
				nodeIds.put(node, parentId); // parentId is the last used nextId, which is the id of node
				final float[] fpCopy = new float[4];
				toPoint(node, fpCopy, aff, zScale);
				nodeCoords.put(node, fpCopy);
			}

			// Prepare next slab or cable
			cableId += 1;
		}

		w.write(" </segments>\n");

		// Define the nature of each cable
		// Each cable requires a unique name
		w.write(" <cables>\n");
		w.write("  <cable id=\"0\" name=\"Soma\">\n   <meta:group>soma_group</meta:group>\n  </cable>\n");
		for (long i=1; i<cableId; i++) {
			final String sid = Long.toString(i);
			w.write("  <cable id=\""); w.write(sid);
			w.write("\" name=\""); w.write(sid);
			if (somaCables.contains(i)) w.write("\" fract_along_parent=\"0.5");
			else w.write("\" fract_along_parent=\"1.0"); // child segments start at the end of the segment
			w.write("\">\n   <meta:group>arbor_group</meta:group>\n  </cable>\n");
		}

		w.write(" </cables>\n</cell>\n");
	}
}