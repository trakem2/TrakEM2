/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2007-2009 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 

You may contact Albert Cardona at acardona at ini.phys.ethz.ch
Institute of Neuroinformatics, University of Zurich / ETH, Switzerland.
**/

package ini.trakem2.persistence;

import ij.ImagePlus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Collection;

public class CacheImagePlus {

	/** An access-ordered list of id keys and ImagePlus values. */
	private final LinkedHashMap<Long,ImagePlus> cache;

	public CacheImagePlus(int initial_size) {
		this.cache = new LinkedHashMap<Long,ImagePlus>(initial_size, 0.75f, true);
	}

	/** No duplicates allowed: if the id exists it's sended to the end and the image is first flushed (if different), then updated with the new one provided. */
	public final void put(final long id, final ImagePlus imp) {
		cache.put(id, imp);
	}

	/** A call to this method puts the element at the end of the list, and returns it. Returns null if not found. */
	public final ImagePlus get(final long id) {
		return cache.get(id);
	}
	/** Remove the Image if found and returns it, without flushing it. Returns null if not found. */
	public final ImagePlus remove(final long id) {
		return cache.remove(id);
	}
	/** Removes and flushes all images, and shrinks arrays. */
	public final Collection<ImagePlus> removeAll() {
		final Collection<ImagePlus> all = cache.values();
		cache.clear();
		return all;
	}
	/** Get the id that refers to the given ImagePlus, if any, or Long.MIN_VALUE if not found. */
	public final long getId(final ImagePlus imp) {
		if (null == imp) return Long.MIN_VALUE;
		for (Iterator<Map.Entry<Long,ImagePlus>> it = cache.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Long,ImagePlus> e = it.next();
			if (e.getValue() == imp) return e.getKey();
		}
		return Long.MIN_VALUE;
	}

	/** THIS IS WRONG: the first is the last accessed, not the least accessed, unfortunately. */
	public final ImagePlus removeFirst() {
		if (cache.size() > 0) {
			final Iterator<ImagePlus> it = cache.values().iterator();
			final ImagePlus imp = it.next();
			it.remove();
			return imp;
		}
		return null;
	}

	public final int size() { return cache.size(); }

	public void debug() {
		System.out.println("Cache size: " + cache.size());
	}
}
