/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2007 Albert Cardona and Rodney Douglas.

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


import ini.trakem2.utils.IJError;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.ListIterator;
import java.util.LinkedList;
import java.awt.Image;

public class FIFOImageMipMaps {

	private final LinkedList<Entry> cache;

	private class Entry {
		final long id;
		final int level;
		Image image;
		Entry (final long id, final int level, final Image image) {
			this.id = id;
			this.level = level;
			this.image = image;
		}
		public boolean equals(final Object ob) {
			final Entry e = (Entry)ob;
			return e.id == id && e.level == level;
		}
		public boolean equals(final long id, final int level) {
			return this.id == id && this.level == level;
		}
	}

	public FIFOImageMipMaps(int initial_size) {
		cache = new LinkedList<Entry>();
	}

	private final Entry rfind(final long id, final int level) {
		final ListIterator<Entry> li = cache.listIterator(cache.size()); // descendingIterator() is from java 1.6 !
		while (li.hasPrevious()) {
			final Entry e = li.previous();
			if (e.equals(id, level)) return e;
		}
		return null;
	}

	/** Replace or put. */
	public void replace(final long id, final Image image, final int level) {
		final Entry e = rfind(id, level);
		if (null == e) put(id, image, level);
		else {
			if (null != e.image) e.image.flush();
			e.image = image;
		}
	}

	/** No duplicates allowed: if the id exists it's sended to the end and the image is first flushed (if different), then updated with the new one provided. */
	public final void put(final long id, final Image image, final int level) {
		final ListIterator<Entry> li = cache.listIterator(cache.size());
		while (li.hasPrevious()) { // images are more likely to be close to the end
			final Entry e = li.previous();
			if (id == e.id && level == e.level) {
				li.remove();
				cache.addLast(e);
				if (image != e.image) {
					e.image.flush();
					e.image = image;
				}
				return;
			}
		}
		// else, new
		cache.addLast(new Entry(id, level, image));
	}

	/** A call to this method puts the element at the end of the list, and returns it. Returns null if not found. */
	public final Image get(final long id, final int level) {
		final ListIterator<Entry> li = cache.listIterator(cache.size());
		while (li.hasPrevious()) { // images are more likely to be close to the end
			final Entry e = li.previous();
			if (id == e.id && level == e.level) {
				li.remove();
				cache.addLast(e);
				return e.image;
			}
		}
		return null;
	}

	/** Find the cached image of the given level or its closest but smaller one, or null if none found. */
	public final Image getClosestBelow(final long id, final int level) {
		Entry ee = null;
		int lev = Integer.MAX_VALUE;
		final ListIterator<Entry> li = cache.listIterator(cache.size());
		while (li.hasPrevious()) { // images are more likely to be close to the end
			final Entry e = li.previous();
			if (e.id != id) continue;
			if (e.level > level) {
				// if exactly smaller image than asked, return it
				if (e.level == level -1) return e.image;
				// if smaller image (larger level) than asked, choose the less smaller
				if (e.level < lev) {
					lev = e.level;
					ee = e;
				}
			}
		}
		if (null != ee) {
			cache.remove(ee); // unfortunaelly, removeLastOcurrence is java 1.6 only
			cache.addLast(ee);
			return ee.image;
		}
		return null;
		// TODO: it's UNNECESSARILY traversing the whole cache!!
	}

	/** Find the cached image of the given level or its closest but larger one, or null if none found. */
	public final Image getClosestAbove(final long id, final int level) {
		int lev = -1;
		Entry ee = null;
		final ListIterator<Entry> li = cache.listIterator(cache.size());
		while (li.hasPrevious()) { // images are more likely to be close to the end
			final Entry e = li.previous();
			if (e.id != id) continue;
			// if equal level as asked, just return it
			if (e.level == level) return e.image;
			// if exactly one above, just return it // may hinder finding an exact one, but potentially cuts down search time a lot
			// if (e.level == level + 1) return e.image; // WOULD NOT BE THE PERFECT IMAGE if the actual asked level is cached at a previous entry.
			if (e.level > lev) {
				lev = e.level;
				ee = e;
			}
		}
		if (null != ee) {
			cache.remove(ee);
			cache.addLast(ee);
			return ee.image;
		}
		return null;
		// TODO: it's UNNECESSARILY traversing the whole cache!!
	}

	/** Remove the Image if found and returns it, without flushing it. Returns null if not found. */
	public final Image remove(final long id, final int level) {
		final ListIterator<Entry> li = cache.listIterator(cache.size());
		while (li.hasPrevious()) {
			final Entry e = li.previous();
			if (id == e.id && level == e.level) {
				li.remove();
				return e.image;
			}
		}
		return null;
	}

	/** Removes and flushes all images, and shrinks arrays. */
	public final void removeAndFlushAll() {
		final ListIterator<Entry> li = cache.listIterator(cache.size());
		while (li.hasPrevious()) {
			final Entry e = li.previous();
			e.image.flush();
		}
		cache.clear();
	}

	/** Remove all awts associated with a level different than 0 (that means all scaled down versions) for any id. */
	public void removeAllPyramids() {
		final ListIterator<Entry> li = cache.listIterator(cache.size());
		while (li.hasPrevious()) {
			final Entry e = li.previous();
			if (e.level > 0) {
				e.image.flush();
				li.remove();
			}
		}
	}

	/** Remove all awts associated with a level different than 0 (that means all scaled down versions) for the given id. */
	public void removePyramid(final long id) {
		final ListIterator<Entry> li = cache.listIterator(cache.size());
		while (li.hasPrevious()) {
			final Entry e = li.previous();
			if (id == e.id && e.level > 0) {
				e.image.flush();
				li.remove();
			}
		}
	}

	/** Remove all images that share the same id (but have different levels). */
	public final ArrayList<Image> remove(final long id) {
		final ArrayList<Image> al = new ArrayList<Image>();
		final ListIterator<Entry> li = cache.listIterator(cache.size());
		while (li.hasPrevious()) {
			final Entry e = li.previous();
			if (id == e.id) {
				al.add(e.image);
				li.remove();
			}
		}
		return al;
	}

	/** Returns a table of level keys and image values that share the same id (that is, belong to the same Patch). */
	public final Hashtable<Integer,Image> getAll(final long id) {
		final Hashtable<Integer,Image> ht = new Hashtable<Integer,Image>();
		final ListIterator<Entry> li = cache.listIterator(cache.size());
		while (li.hasPrevious()) {
			final Entry e = li.previous();
			if (id == e.id) ht.put(e.level, e.image);
		}
		return ht;
	}

	/** Remove and flush away all images that share the same id. */
	public final void removeAndFlush(final long id) {
		final ListIterator<Entry> li = cache.listIterator(cache.size());
		while (li.hasPrevious()) {
			final Entry e = li.previous();
			if (id == e.id) {
				e.image.flush();
				li.remove();
			}
		}
	}

	/** Remove the given index and return it, returns null if outside range. */
	public final Image remove(int i) {
		if (i < 0 || i >= cache.size()) return null;
		return cache.remove(i).image;
	}

	/** Remove the first element and return it. Returns null if none. The underlaying arrays are untouched besides nullifying the proper pointer. */
	public final Image removeFirst() {
		return cache.removeFirst().image;
	}

	/** Checks if there's any image at all for the given id. */
	public final boolean contains(final long id) {
		final ListIterator<Entry> li = cache.listIterator(cache.size());
		while (li.hasPrevious()) {
			final Entry e = li.previous();
			if (id == e.id) return true;
		}
		return false;
	}

	/** Checks if there's any image for the given id and level. */
	public final boolean contains(final long id, final int level) {
		return -1 != cache.lastIndexOf(new Entry(id, level, null));
	}

	public int size() { return cache.size(); }

	public void debug() {}

	/** Does nothing. */
	public void gc() {}
}

