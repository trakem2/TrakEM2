package ini.trakem2.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;

/** Following pseudo-code from:
 *  Ulrik Brandes. 2001. A faster algorithm for betweenness centrality.
 *  Journal of Mathematical Sociology 25(2):163-177, (2001). */
public class Centrality {

	/** Like @see compute, but operates on a copy of all Vertex instances,
	 * and return a Collection with the same order as @param vs. */
	static public final ArrayList<Vertex> safeCompute(final Collection<Vertex> vs) {
		final HashMap<Vertex,Vertex> rel = new HashMap<Vertex,Vertex>();
		for (final Vertex v : vs) {
			rel.put(v, new Vertex());
		}
		ArrayList<Vertex> copies = new ArrayList<Vertex>(vs.size());
		for (final Vertex v : vs) {
			final Vertex copy = rel.get(v);
			copy.neighbors = new Vertex[v.neighbors.length];
			for (int i=0; i<v.neighbors.length; i++) {
				copy.neighbors[i] = rel.get(v.neighbors[i]);
			}
			copies.add(copy);
		}
		compute(copies);
		return copies;
	}

	/** Computes betweenness centrality of each vertex in the collection of vertices @param vs
	 *  where all vertices are part of the same graph.
	 *  Assumes the internal variables of each Vertex are reset to its initialization values,
	 *  and that the neighbors array is proper as well.
	 *  When done, the centrality of each Vertex is set in the homonymous Vertex field. */
	static public final void compute(final Collection<Vertex> vs) {
		if (1 == vs.size()) {
			return;
		}
		final LinkedList<Vertex> stack = new LinkedList<Vertex>();
		final LinkedList<Vertex> queue = new LinkedList<Vertex>();

		for (final Vertex s : vs) {
			// Reset all:
			for (final Vertex t : vs) {
				t.predecessors.clear();
				t.sigma = 0;
				t.d = -1;
			}
			// Prepare s:
			s.sigma = 1;
			s.d = 0;
			queue.add(s);
			//
			while (!queue.isEmpty()) {
				final Vertex v = queue.removeFirst();
				stack.add(v);
				for (final Vertex w : v.neighbors) {
					// w found for the first time?
					if (-1 == w.d) {
						queue.add(w);
						w.d = v.d + 1;
					}
					// shortest path to w via v?
					if (w.d == v.d + 1) {
						w.sigma += v.sigma; // sigma is always 1 in a tree
						w.predecessors.add(v);
					}
				}
			}
			for (final Vertex v : vs) {
				v.delta = 0;
			}
			while (!stack.isEmpty()) {
				final Vertex w = stack.removeLast();
				for (final Vertex v : w.predecessors) {
					v.delta += (v.sigma / (float)w.sigma) * (1 + w.delta); // sigma is always 1 in a tree
				}
				if (w != s) {
					w.centrality += w.delta;
				}
			}
		}
	}
}