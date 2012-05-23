package ini.trakem2.io;

import ij.measure.Calibration;
import ini.trakem2.display.AreaTree;
import ini.trakem2.display.Connector;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Node;
import ini.trakem2.display.Tree;
import ini.trakem2.display.Treeline;
import ini.trakem2.utils.Utils;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
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
		w.write("</meta:notes>\n");
		w.write(" <meta:properties>\n");
		w.write("  <meta:property tag=\"Neuron type\" value=\"manually reconstructed\" />\n");
		w.write(" </meta:properties>\n");
		w.write(" <segments xmlns=\"http://morphml.org/morphml/schema\">\n");
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

	static private final class HalfSynapse {
		private Connector c;
		private Tree<?> t;
		@SuppressWarnings("unused")
		private Node<?> node;
		private long segmentId;

		/** A pre- or a post-synaptic site, located at {@param node} with which a segment with id {@param segmentId} was made.*/
		HalfSynapse(final Connector c, final Tree<?> t, final Node<?> node, final long segmentId) {
			this.c = c;
			this.t = t;
			this.node = node;
			this.segmentId = segmentId;
		}
	}
	
	static private final class Synapse {
		private final HalfSynapse pre, post;
		Synapse(final HalfSynapse pre, final HalfSynapse post) {
			this.pre = pre;
			this.post = post;
		}
	}

	static private final void collectConnectors(final Node<?> node, final Tree<?> t,
			final float[] nodeWorldCoords, final long segmentId,
			final List<HalfSynapse> pre, final List<HalfSynapse> post)
	{
		for (final Displayable d : t.getLayerSet().findZDisplayables(Connector.class, node.getLayer(), (int)nodeWorldCoords[0], (int)nodeWorldCoords[1], false)) {
			final Connector c = (Connector) d;
			if (c.intersectsOrigin(nodeWorldCoords[0], nodeWorldCoords[1], node.getLayer())) {
				pre.add(new HalfSynapse(c, t, node, segmentId));
			} else {
				post.add(new HalfSynapse(c, t, node, segmentId));
			}
		}
	}
	
	static private final class TreePair {
		final Tree<?> source, target;
		TreePair(Tree<?> source, Tree<?> target) {
			this.source = source;
			this.target = target;
		}
		public final boolean equals(final Object ob) {
			final TreePair p = (TreePair) ob;
			return (source == p.source && target == p.target);
		}
	}

	static private final double scaleToMicrometers(final Calibration cal) {
		final double scale;
		final String unit = cal.getUnit().trim().toLowerCase();
		if (unit.equals("nanometer") || unit.equals("nm")) scale = 0.001;
		else if (unit.equals("micrometer") || unit.equals("µm") || unit.equals("um")) scale = 1;
		else if (unit.equals("milimeter") || unit.equals("mm")) scale = 1000;
		else if (unit.equals("meter") || unit.equals("m")) scale = 1000000;
		else {
			scale = 1;
			Utils.logAll("UNKNOWN unit '" + unit + "' -- using scale of 1.");
		}
		return scale;
	}
	
	static public final void exportMorphML(final Collection<Tree<?>> trees, final Writer w) throws Exception {
		exportMorphML(new HashSet<Tree<?>>(trees), w);
	}

	static public final void exportMorphML(final Set<Tree<?>> trees, final Writer w) throws Exception {
		if (trees.isEmpty()) return;
		
		// Header
		w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		w.write("<!-- Exported from TrakEM2 '" + Utils.version + "' at " + new Date() + "\nTrakEM2 software by Albert Cardona, Institute of Neuroinformatics of the University of Zurich and ETH Zurich -->\n");
		w.write("<morphml xmlns=\"http://morphml.org/morphml/schema\"\n");
		w.write("  xmlns:meta=\"http://morphml.org/metadata/schema\"\n");
		w.write("  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
		w.write("  xsi:schemaLocation=\"http://morphml.org/morphml/schema http://www.neuroml.org/NeuroMLValidator/NeuroMLFiles/Schemata/v1.8.1/Level1/MorphML_v1.8.1.xsd\"\n");
		w.write("  length_units=\"micrometer\">\n<cells>\n");
		
		// Scale units to micrometers
		final Calibration cal = trees.iterator().next().getLayerSet().getCalibration();
		final double scale = scaleToMicrometers(cal);
		
		final AffineTransform scale2d = new AffineTransform(cal.pixelWidth * scale, 0, 0, cal.pixelHeight * scale, 0, 0);
		final double zScale = cal.pixelWidth * scale; // not pixelDepth
		
		// Each Tree is a cell
		for (final Tree<?> t : trees)
		{
			if (null == t.getRoot()) continue;
	
			exportMorphMLCell(w, t, trees, null, null, scale2d, zScale);
		}
		
		w.write("</cells>\n</morphml>\n");
	}
	
	static public final void exportNeuroML(final Collection<Tree<?>> trees, final Writer w) throws Exception {
		exportNeuroML(new HashSet<Tree<?>>(trees), w);
	}

	/** Export to NeuroML 1.8.3, with synapses.
	 * Every {@link Tree} is represented by a &lt;cell&gt;, and an instance of that &lt;cell&gt;
	 * is represented by a &lt;population&gt; of one single cell. */
	static public final void exportNeuroML(final Set<Tree<?>> trees, final Writer w) throws Exception
	{
		if (trees.isEmpty()) return;
		
		// Header
		w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
		 + "<!-- Exported from TrakEM2 '" + Utils.version + "' at " + new Date() + "\nTrakEM2 software by Albert Cardona, Institute of Neuroinformatics of the University of Zurich and ETH Zurich -->\n"
		 + "<neuroml xmlns=\"http://morphml.org/neuroml/schema\"\n"
		 + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
		 + " xmlns:net=\"http://morphml.org/networkml/schema\"\n" 
		 + " xmlns:mml=\"http://morphml.org/morphml/schema\"\n"
		 + " xmlns:meta=\"http://morphml.org/metadata/schema\"\n"
		 + " xmlns:bio=\"http://morphml.org/biophysics/schema\"\n"
		 + " xmlns:cml=\"http://morphml.org/channelml/schema\"\n"
		 + " xsi:schemaLocation=\"http://morphml.org/neuroml/schema http://www.neuroml.org/NeuroMLValidator/NeuroMLFiles/Schemata/v1.8.1/Level3/NeuroML_Level3_v1.8.1.xsd\"\n"
		 + " length_units=\"micrometer\">\n");
		
		final List<HalfSynapse> presynaptic = new ArrayList<HalfSynapse>();
		final List<HalfSynapse> postsynaptic = new ArrayList<HalfSynapse>();

		// Scale units to micrometers
		final Calibration cal = trees.iterator().next().getLayerSet().getCalibration();
		final double scale = scaleToMicrometers(cal);
		
		final AffineTransform scale2d = new AffineTransform(cal.pixelWidth * scale, 0, 0, cal.pixelHeight * scale, 0, 0);
		final double zScale = cal.pixelWidth * scale; // not pixelDepth
		
		w.write("<cells>\n");
		
		// Each Tree is a cell
		for (final Tree<?> t : trees)
		{
			if (null == t.getRoot()) continue;
	
			exportMorphMLCell(w, t, trees, presynaptic, postsynaptic, scale2d, zScale);
		}
		
		w.write("</cells>\n");

		// Write a a population of cell for every Tree, where each population has only one cell at 0,0,0.
		// If the id=10, then the name is p10 and the type is t10.
		w.write("<populations xmlns=\"http://morphml.org/networkml/schema\">\n");
		for (final Tree<?> t : trees) {
			w.write(" <population name=\"p");
			final String sid = Long.toString(t.getId());
			w.write(sid);
			w.write("\" cell_type=\"t");
			w.write(sid);
			w.write("\">\n  <instances size=\"1\">\n   <instance id=\"0\"><location x=\"0\" y=\"0\" z=\"0\"/></instance>\n  </instances>\n </population>\n");
		}
		w.write("</populations>\n");

		// Write a project group with all the synapses among the members of the set of trees.
		w.write("<projections units=\"Physiological Units\" xmlns=\"http://morphml.org/networkml/schema\">\n");

		// Figure out which pre connect to which post: the Connector instance is shared, so use it as key
		final Map<Connector,HalfSynapse> cpre = new HashMap<Connector,HalfSynapse>();
		for (final HalfSynapse syn : presynaptic) {
			cpre.put(syn.c, syn);
		}
		final Map<TreePair,List<Synapse>> pairs = new HashMap<TreePair,List<Synapse>>();
		for (final HalfSynapse post : postsynaptic) {
			final HalfSynapse pre = cpre.get(post.c);
			if (null == pre) continue; // Does not originate within the set of trees
			// pre and post share the same Connector
			final TreePair pair = new TreePair(pre.t, post.t);
			List<Synapse> ls = pairs.get(pair);
			if (null == ls) {
				ls = new ArrayList<Synapse>();
				pairs.put(pair, ls);
			}
			ls.add(new Synapse(pre, post));
		}

		for (final Map.Entry<TreePair,List<Synapse>> e : pairs.entrySet()) {
			// Write synapse between pre and post
			final TreePair pair = e.getKey();
			w.write("  <projection name=\"NetworkConnection\" source=\"p");
			w.write(Long.toString(pair.source.getId()));
			w.write("\" target=\"p");
			w.write(Long.toString(pair.target.getId()));
			w.write("\">\n");
			w.write("   <synapse_props synapse_type=\"DoubExpSynA\" internal_delay=\"5\" weight=\"1\" threshold=\"-20\"/>\n");
			w.write("   <connections size=\"");
			final List<Synapse> ls = e.getValue();
			w.write(Integer.toString(ls.size()));
			w.write("\">\n");
			int cid = 0;
			for (final Synapse syn : ls) {
				w.write("    <connection id=\"");
				w.write(Integer.toString(cid));
				w.write("\" pre_cell_id=\"0\" pre_segment_id=\"");
				w.write(Long.toString(syn.pre.segmentId));
				w.write("\" pre_fraction_along=\"0.5\" post_cell_id=\"0\" post_segment_id=\"");
				w.write(Long.toString(syn.post.segmentId));
				w.write("\"/>\n");
				
				cid += 1;
			}
			w.write("   </connections>\n");
			
			w.write("  </projection>\n");
		}
		w.write(" </projections>\n");

		w.write("</neuroml>\n");
	}

	/** Without headers, just the cell block for a single AreaTree.
	 *  Works by duplicating the AreaTree as a Treeline. */
	static private final void exportMorphMLCell(final Writer w, final Tree<?> t,
			final Set<Tree<?>> trees, final List<HalfSynapse> pre, final List<HalfSynapse> post,
			final AffineTransform scale2d, final double zScale) throws Exception
	{
		if (t instanceof Treeline) {
			exportMorphMLCell(w, (Treeline)t, trees, pre, post, scale2d, zScale);
		} else if (t instanceof AreaTree) {
			exportMorphMLCell(w, Tree.copyAs((AreaTree)t, Treeline.class, Treeline.RadiusNode.class), trees, pre, post, scale2d, zScale);
		}
	}

	/** Without headers, just the cell block for a single Treeline.
	 * If pre is null, then synapses are not collected. */
	static private final void exportMorphMLCell(final Writer w, final Treeline t,
			final Set<Tree<?>> trees, final List<HalfSynapse> pre, final List<HalfSynapse> post,
			final AffineTransform scale2d, final double zScale) throws IOException
	{	
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
		long nextSegmentId = 0;
		long cableId = 0;
		final Node<Float> root = t.getRoot();

		toPoint(root, fp, aff, zScale);
		writeSomaSegment(w, fp); // a dummy segment that has no length, and with a cableId of 0.
		if (null != pre) collectConnectors(root, t, fp, 0, pre, post);

		// Prepare
		nodeIds.put(root, nextSegmentId);
		nodeCoords.put(root, fp.clone());
		nextSegmentId += 1;
		cableId += 1;

		// All cables that come out of the Soma (the root) require a special tag:
		final HashSet<Long> somaCables = new HashSet<Long>();

		// Iterate all cables (all slabs; here a slab is synonym with cable, even if in NeuroML it doesn't have to be)
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
				writeCableSegment(w, fp, nextSegmentId, parentId, parentCoords, sCableId);
				// Inspect and collect synapses originating at this node
				if (null != pre) collectConnectors(seg, t, fp, nextSegmentId, pre, post);
				// Prepare next segment in the cable
				parentId = nextSegmentId;
				nextSegmentId += 1;
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
		w.write(" <cables xmlns=\"http://morphml.org/morphml/schema\">\n");
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