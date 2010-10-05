package ini.trakem2.analysis;

import ini.trakem2.display.Node;
import ini.trakem2.display.Tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/** Following pseudo-code from:
 *  Ulrik Brandes. 2001. A faster algorithm for betweenness centrality.
 *  Journal of Mathematical Sociology 25(2):163-177, (2001). */
public class Centrality {

	static private class Vertex<T> {
		final Node<T> node;
		float centrality = 0;
		/** Number of short paths passing through this vertex. */
		//long sigma = 0; // ALWAYS 1 in a tree
		Vertex<T>[] neighbors = null;
		//
		/** Length of the path? */
		long d = -1;
		float delta = 0;
		final ArrayList<Vertex<T>> predecessors = new ArrayList<Vertex<T>>();
		Vertex(final Node<T> node) {
			this.node = node;
		}
		@SuppressWarnings("unchecked")
		Vertex<T>[] neighbors(final HashMap<Node<T>,Vertex<T>> m) {
			if (null == neighbors) {
				final Node<T> parent = node.getParent();
				neighbors = (Vertex<T>[])new Vertex[(null == parent ? 0 : 1) + node.getChildrenCount()];
				int next = 0;
				if (null != parent) neighbors[next++] = m.get(parent);
				for (final Node<T> child : node.getChildrenNodes()) {
					neighbors[next++] = m.get(child);
				}
			}
			return neighbors;
		}
	}

	/** Computes betweenness centrality of each node in the @param tree. */
	static public final<T> HashMap<Node<T>,Float> compute(final Tree<T> tree) {
		final HashMap<Node<T>,Vertex<T>> m = new HashMap<Node<T>,Vertex<T>>();
		for (final Node<T> node : tree.getRoot().getSubtreeNodes()) {
			m.put(node, new Vertex<T>(node));
		}
		final LinkedList<Vertex<T>> stack = new LinkedList<Vertex<T>>();
		final LinkedList<Vertex<T>> queue = new LinkedList<Vertex<T>>();

		for (final Vertex<T> s : m.values()) {
			// Reset all:
			for (final Vertex<T> t : m.values()) {
				t.predecessors.clear();
				//t.sigma = 0;
				t.d = -1;
			}
			// Prepare s:
			//s.sigma = 1;
			s.d = 0;
			queue.add(s);
			//
			while (!queue.isEmpty()) {
				final Vertex<T> v = queue.removeFirst();
				stack.add(v);
				for (final Vertex<T> w : v.neighbors(m)) {
					// w found for the first time?
					if (-1 == w.d) {
						queue.add(w);
						w.d = v.d + 1;
					}
					// shortest path to w via v?
					if (w.d == v.d + 1) {
						/*w.sigma += v.sigma;*/ // sigma is always 1 in a tree
						w.predecessors.add(v);
					}
				}
			}
			for (final Vertex<T> v : m.values()) {
				v.delta = 0;
			}
			while (!stack.isEmpty()) {
				final Vertex<T> w = stack.removeLast();
				for (final Vertex<T> v : w.predecessors) {
					v.delta += /*(v.sigma / (float)w.sigma) * */ (1 + w.delta); // sigma is always 1 in a tree
				}
				if (w != s) {
					w.centrality += w.delta;
				}
			}
		}

		final HashMap<Node<T>,Float> cs = new HashMap<Node<T>,Float>();
		for (final Map.Entry<Node<T>,Vertex<T>> e : m.entrySet()) {
			cs.put(e.getKey(), e.getValue().centrality);
		}
		return cs;
	}
}