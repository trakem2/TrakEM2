package ini.trakem2.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** A class to express a Vertex in a graph. */
public class Vertex {
	public float centrality = 0;

	/** Number of short paths passing through this vertex. */
	public long sigma = 0; // ALWAYS 1 in a tree

	public Vertex[] neighbors = null;

	/** Length of the path. */
	public long d = -1;

	public Vertex() {}

	// Temporary variables to use in the computation of centrality
	float delta = 0;
	final ArrayList<Vertex> predecessors = new ArrayList<Vertex>();

	public void forgetNeighbors(final Set<Vertex> ws) {
		final HashSet<Vertex> vs = new HashSet<Vertex>();
		for (final Vertex v : neighbors) vs.add(v);
		vs.removeAll(ws);
		neighbors = (Vertex[]) vs.toArray();
	}
}
