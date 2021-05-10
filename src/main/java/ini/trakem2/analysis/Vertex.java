/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2021 Albert Cardona, Stephan Saalfeld and others.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package ini.trakem2.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
			// neighbors will be empty
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
	
	public boolean isBranching() {
		return neighbors.size() > 2;
	}

	public boolean isEnding() {
		return 1 == neighbors.size();
	}

	/** From this vertex to the next branch vertex or end vertex, both inclusive.
	 *  The @param exclude is the neighbor to ignore.
	 *  @throws IllegalArgumentException if @param exclude is not a neighbor. */
	public List<Vertex<T>> getBranch(final Vertex<T> parent) {
		if (!neighbors.contains(parent)) throw new IllegalArgumentException("'parent' vertex is not a neighbor");
		//
		final List<Vertex<T>> chain = new ArrayList<Vertex<T>>();
		chain.add(this);
		if (isBranching()) {
			return chain;
		}
		// Iterate until the next branch or end vertex.
		Vertex<T> o = this,
		          p = parent;
		Collection<Vertex<T>> c = this.neighbors;

		while (true) {
			if (1 == c.size()) {
				return chain;
			}
			for (final Vertex<T> v : c) {
				if (v == p) continue;
				chain.add(v);
				if (v.isBranching() || v.isEnding()) {
					return chain;
				}
				p = o;
				o = v;
				c = v.neighbors;
			}
		}
	}
}
