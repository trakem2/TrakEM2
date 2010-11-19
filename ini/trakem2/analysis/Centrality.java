package ini.trakem2.analysis;

import ij.measure.Calibration;
import ini.trakem2.display.Node;
import ini.trakem2.utils.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.vecmath.Point3f;

/** Following pseudo-code from:
 *  Ulrik Brandes. 2001. A faster algorithm for betweenness centrality.
 *  Journal of Mathematical Sociology 25(2):163-177, (2001). */
public class Centrality {

	/** Like @see compute, but operates on a copy of all Vertex instances,
	 * and return a Collection with the same order as @param vs. */
	static public final<T> ArrayList<Vertex<T>> safeCompute(final Collection<Vertex<T>> vs) {
		final ArrayList<Vertex<T>> copies = Vertex.clone(vs);
		compute(copies);
		return copies;
	}

	/** Computes betweenness centrality of each vertex in the collection of vertices @param vs
	 *  where all vertices are part of the same graph.
	 *  Assumes the internal variables of each Vertex are reset to its initialization values,
	 *  and that the neighbors array is proper as well.
	 *  When done, the centrality of each Vertex is set in the homonymous Vertex field. */
	static public final<T> void compute(final Collection<Vertex<T>> vs) {
		if (1 == vs.size()) {
			return;
		}
		final LinkedList<Vertex<T>> stack = new LinkedList<Vertex<T>>();
		final LinkedList<Vertex<T>> queue = new LinkedList<Vertex<T>>();

		for (final Vertex<T> s : vs) {
			// Reset all:
			for (final Vertex<T> t : vs) {
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
				final Vertex<T> v = queue.removeFirst();
				stack.add(v);
				for (final Vertex<T> w : v.neighbors) {
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
			for (final Vertex<T> v : vs) {
				v.delta = 0;
			}
			while (!stack.isEmpty()) {
				final Vertex<T> w = stack.removeLast();
				for (final Vertex<T> v : w.predecessors) {
					v.delta += (v.sigma / (float)w.sigma) * (1 + w.delta); // sigma is always 1 in a tree
				}
				if (w != s) {
					w.centrality += w.delta;
				}
			}
		}
	}

	static private final <T> Point3f world(final Vertex<T> v) {
		if (null == v) return null;
		Node<?> nd = (Node<?>)v.data;
		Point3f p = new Point3f(nd.getX(), nd.getY(), nd.getLayer().getParent().indexOf(nd.getLayer()));
		Calibration cal = nd.getLayer().getParent().getCalibration();
		p.x = p.x * (float)cal.pixelWidth + 2505;
		p.y = p.y * (float)cal.pixelHeight + 3141;
		return p;
	}
	
	/** Find the chain of nodes, over branch points if necessary, that have not yet been processed. */
	static private final <T> List<Vertex<T>> findChain(
			final Vertex<T> origin,
			final Vertex<T> parent,
			final Set<Vertex<T>> processed) {
		Utils.log2("findChain: searching for origin " + world(origin));
		final ArrayList<Vertex<T>> chain = new ArrayList<Vertex<T>>();
		Utils.log2("-- added to chain: " + world(origin));
		chain.add(origin);

		Vertex<T> o = origin;
		Vertex<T> p = parent;

		A: while (true) {
			if (chain.size() > 50) { Utils.log2("-- infinite loop"); return chain; }
			if (1 == o.neighbors.size()) { Utils.log2("-- reached end point"); break; }
			Vertex<T> o2 = o;
			for (final Vertex<T> v : o.neighbors) {
				if (v == p || processed.contains(v) || v.centrality < p.centrality) {
					continue;
				}
				chain.add(v);
				Utils.log2("-- added to chain: " + world(v));

				p = o;
				o = v;
				continue A; // there can only be one unexplored path
			}
			if (o2 == o) break;
		}
		Utils.log2("findChain: returning chain of " + chain.size());
		return chain;
	}

	static public final<T> ArrayList<EtchingStep<T>> branchWise(final Collection<Vertex<T>> vs_, final int etching_multiplier) {

		// Copy
		final Set<Vertex<T>> vs = new HashSet<Vertex<T>>(Vertex.clone(vs_));

		// Map of original vertex instances
		final HashMap<T,Vertex<T>> m = new HashMap<T,Vertex<T>>();
		for (final Vertex<T> v : vs_) {
			m.put(v.data, v);
		}

		// A set of the remaining branch vertices
		final Set<Vertex<T>> branch_vertices = new HashSet<Vertex<T>>();
		for (final Vertex<T> v : vs) {
			if (v.getNeighborCount() > 2) branch_vertices.add(v);
		}

		// Map of the count of remaining branch vertices and vertices removed at that step
		final ArrayList<EtchingStep<T>> steps = new ArrayList<EtchingStep<T>>();

		final HashSet<Vertex<T>> processed = new HashSet<Vertex<T>>();

		while (vs.size() > 0) {
			// Reset all internal vars related to computing centrality
			for (final Vertex<T> v : vs) {
				v.reset();
			}
			// Recompute centrality for the now smaller graph
			Centrality.compute(vs);

			// Remove all vertices whose centrality falls below a certain threshold
			final HashSet<Vertex<T>> removed = new HashSet<Vertex<T>>();
			final int vs_size = vs.size();
			for (final Iterator<Vertex<T>> it = vs.iterator(); it.hasNext(); ) {
				final Vertex<T> v = it.next();
				if (v.centrality < etching_multiplier * vs_size) {
					it.remove();
					removed.add(v);
				}
			}
			
			// Determine which branches have been removed at this etching step
			System.out.println("## remaining branch points: " + branch_vertices.size());

			final Set<Collection<Vertex<T>>> etched_branches = new HashSet<Collection<Vertex<T>>>();
			final Set<Vertex<T>> tmp = new HashSet<Vertex<T>>();
			for (final Vertex<T> r : removed) {
				for (final Vertex<T> w : r.neighbors) {
					if (w.isBranching() && w.centrality >= r.centrality) {
						final List<Vertex<T>> branch = findChain(m.get(r.data), m.get(w.data), processed);
						processed.addAll(branch);
						etched_branches.add(branch);
					}
				}
			}

			if (etched_branches.size() > 0) {
				steps.add(new EtchingStep<T>(branch_vertices.size(), etched_branches));
			}

			// Fix neighbors of remaining vertices
			for (final Vertex<T> v : removed) {
				for (final Iterator<Vertex<T>> it = v.neighbors.iterator(); it.hasNext(); ) {
					final Vertex<T> w = it.next();
					if (removed.contains(w)) {
						it.remove();
						continue;
					}
					// to the vertex that remains, remove the vertex v from its neighbors:
					w.neighbors.remove(v);
				}
			}
			// Remove branch vertices which aren't branch vertices anymore
			for (final Iterator<Vertex<T>> it = branch_vertices.iterator(); it.hasNext(); ) {
				final Vertex<T> v = it.next();
				if (v.neighbors.size() < 3 || removed.contains(v)) {
					it.remove();
				}
			}
			Utils.log2("branch vertices: " + branch_vertices.size() + ", vs: " + vs.size() + ", removed: " + removed.size());

			if (0 == branch_vertices.size()) {
				// The last, central branch
				/*
				vs.addAll(removed); // undo the etching
				HashSet<Collection<Vertex<T>>> bs = new HashSet<Collection<Vertex<T>>>();
				bs.add(vs);
				steps.add(new EtchingStep<T>(0, bs));
				*/
				break;
			}
		}

		for (final EtchingStep<T> es : steps) {
			for (final Collection<Vertex<T>> b : es.branches) {
				List<Vertex<T>> b2 = new ArrayList<Vertex<T>>(b);
				b.clear();
				for (final Vertex<T> v : b2) {
					b.add(m.get(v.data));
				}
			}
		}

		// The last, central branch
		Set<T> done = new HashSet<T>();
		for (final Vertex<T> v : processed) {
			done.add(v.data);
		}
		HashMap<T,Vertex<T>> last = new HashMap<T,Vertex<T>>(m);
		last.keySet().removeAll(done);
		HashSet<Collection<Vertex<T>>> bs = new HashSet<Collection<Vertex<T>>>();
		bs.add(last.values());
		steps.add(new EtchingStep<T>(0, bs));

		return steps;
	}

	/** An entry of the number of remaining branch vertices versus
	 *  the set of branches (each as a List<Vertex<T>>) removed. */
	static public class EtchingStep<T> implements Map.Entry<Integer,Set<Collection<Vertex<T>>>> {
		public int remaining_branch_vertices = 0;
		public Set<Collection<Vertex<T>>> branches = null;
		public EtchingStep(final int remaining_branch_vertices, final Set<Collection<Vertex<T>>> branches) {
			this.remaining_branch_vertices = remaining_branch_vertices;
			this.branches = branches;
		}
		@Override
		public Integer getKey() {
			return remaining_branch_vertices;
		}
		@Override
		public Set<Collection<Vertex<T>>> getValue() {
			return branches;
		}
		@Override
		public Set<Collection<Vertex<T>>> setValue(final Set<Collection<Vertex<T>>> value) {
			throw new UnsupportedOperationException();
		}
	}
}
