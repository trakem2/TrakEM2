package ini.trakem2.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** A class to express a Vertex in a graph.
 *  This class is stateful and not thread-safe. */
public class Vertex<T> {
	public float centrality = 0;

	/** Number of short paths passing through this vertex. */
	public long sigma = 0; // ALWAYS 1 in a tree

	public final Set<Vertex<T>> neighbors = new HashSet<Vertex<T>>();

	/** Length of the path. */
	public long d = -1;

	/** The data associated with this node, if any. */
	public T data;
	
	public Vertex(final T data) {
		this.data = data;
	}

	// Temporary variables to use in the computation of centrality
	float delta = 0;
	final ArrayList<Vertex<T>> predecessors = new ArrayList<Vertex<T>>();

	/** All but neighbors. */
	protected void reset() {
		centrality = 0;
		sigma = 0;
		d = -1;
		//
		delta = 0;
		predecessors.clear();
	}

	public int getNeighborCount() {
		return neighbors.size();
	}

	/** Clone a collection of vertices, preserving the neighbors.
	 *  @return An ArrayList of Vertex instances in the same order as @param vs delivered them. */
	static public<T> ArrayList<Vertex<T>> clone(final Collection<Vertex<T>> vs) {
		final ArrayList<Vertex<T>> copies = new ArrayList<Vertex<T>>(vs.size());
		if (1 == vs.size()) {
			// neighbors will be null
			copies.add(new Vertex<T>(vs.iterator().next().data));
			return copies;
		}
		final HashMap<Vertex<T>,Vertex<T>> rel = new HashMap<Vertex<T>,Vertex<T>>();
		for (final Vertex<T> v : vs) {
			rel.put(v, new Vertex<T>(v.data));
		}
		for (final Vertex<T> v : vs) {
			final Vertex<T> copy = rel.get(v);
			for (final Vertex<T> w : v.neighbors) {
				copy.neighbors.add(rel.get(w));
			}
			copies.add(copy);
		}
		return copies;
	}
}
