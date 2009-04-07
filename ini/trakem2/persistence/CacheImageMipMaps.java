/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2008 Albert Cardona and Stephan Preibisch.

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
import java.util.HashMap;
import java.util.ListIterator;
import java.util.LinkedList;
import java.util.Iterator;
import java.util.Map;
import java.awt.Image;

/** A cache for TrakEM2's rolling memory of java.awt.Image instances.
 *  Uses both a list and a map. When the list goes beyond a certain LINEAR_SEARCH_LIMIT, then the map is used.
 *  Each image is added as a CacheImageMipMaps.Entry, which stores the image, the corresponding Patch id and the level of the image (0, 1, 2 ... mipmap level of descresing power of 2 sizes).
 *  The map contains unique Patch id keys versus Image arrays of values, where the array index is the index.
 */
public class CacheImageMipMaps {

	private final LinkedList<Entry> cache;
	private final HashMap<Long,Image[]> map = new HashMap<Long,Image[]>();
	private boolean use_map = false;
	public static int LINEAR_SEARCH_LIMIT = 0;

	private class Entry {
		final long id;
		final int level;
		Image image;
		Entry (final long id, final int level, final Image image) {
			this.id = id;
			this.level = level;
			this.image = image;
		}
		public final boolean equals(final Object ob) {
			final Entry e = (Entry)ob;
			return e.id == id && e.level == level;
		}
		public final boolean equals(final long id, final int level) {
			return this.id == id && this.level == level;
		}
	}

	public CacheImageMipMaps(int initial_size) {
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

	static private final int computeLevel(final int i) {
		return (int)(0.5 + ((Math.log(i) - Math.log(32)) / Math.log(2))) + 1;
	}

	/** The position in the array is the Math.max(width, height) of an image. */
	private final static int[] max_levels = new int[50000]; // don't change to smaller than 33. Here 50000 is the maximum width or height for which precomputed mipmap levels will exist.
	static {
		// from 0 to 31 all zeros
		for (int i=32; i<max_levels.length; i++) {
			max_levels[i] = computeLevel(i);
		}
	}

	private final int maxLevel(final Image image, final int starting_level) {
		final int w = image.getWidth(null);
		final int h = image.getHeight(null);
		/*
		int max_level = starting_level;

		while (w > 32 || h > 32) {
			w /= 2;
			h /= 2;
			max_level++;
		}
		return max_level;
		*/

		final int max = Math.max(w, h);
		if (max >= max_levels.length) {
			//if (max <= 32) return starting_level;
			return starting_level + computeLevel(max);
		} else {
			return starting_level + max_levels[max];
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
					// replace
					e.image.flush();
					e.image = image;
					// replace in map
					if (use_map) {
						final Image[] im = map.get(id);
						if (null != im) im[level] = image;
					}
				}
				return;
			}
		}
		// else, new
		cache.addLast(new Entry(id, level, image));

		if (use_map) {
			// add new to the map
			Image[] images = map.get(id);
			try {
				if (null == images) {
					//System.out.println("CREATION maxLevel, level, image.width: " + maxLevel(image, level) + ", " + level + ", " + image.getWidth(null));
					images = new Image[maxLevel(image, level)];
					images[level] = image;
					map.put(id, images);
				} else {
					images[level] = image;
				}
			} catch (Exception e) {
				System.out.println("length of images[]: " + images.length);
				System.out.println("level to add: " + level);
				System.out.println("size of the image: " + image.getWidth(null));
				System.out.println("maxlevel is: " + maxLevel(image, level));
			}
		} else if (cache.size() >= LINEAR_SEARCH_LIMIT) { // create the map one step before it's used, hence the >= not > alone.
			// initialize map
			final ListIterator<Entry> lim = cache.listIterator(0);
			while (lim.hasNext()) {
				final Entry e = lim.next();
				if (!map.containsKey(e.id)) {
					final Image[] images = new Image[maxLevel(image, level)];
					images[e.level] = e.image;
					map.put(e.id, images);
				} else {
					final Image[] images = map.get(e.id);
					images[e.level] = e.image;
				}
			}
			use_map = true;
			//System.out.println("CREATED map");
		}
	}

	/** A call to this method puts the element at the end of the list, and returns it. Returns null if not found. */
	public final Image get(final long id, final int level) {
		if (0 == LINEAR_SEARCH_LIMIT) return getFromMap(id, level);
		final ListIterator<Entry> li = cache.listIterator(cache.size());
		int i = 0;
		while (li.hasPrevious()) { // images are more likely to be close to the end
			if (i > LINEAR_SEARCH_LIMIT) {
				return getFromMap(id, level);
			}
			i++;
			///
			final Entry e = li.previous();
			if (id == e.id && level == e.level) {
				li.remove();
				cache.addLast(e);
				return e.image;
			}
		}
		return null;
	}

	private final Image getFromMap(final long id, final int level) {
		final Image[] images = map.get(id);
		if (null == images) return null;
		return level < images.length ? images[level] : null;
	}

	private final Image getAboveFromMap(final long id, final int level) {
		final Image[] images = map.get(id);
		if (null == images) return null;

		for (int i=Math.min(level, images.length-1); i>-1; i--) {
			if (null != images[i]) return images[i];
		}
		return null;
	}

	private final Image getBelowFromMap(final long id, final int level) {
		final Image[] images = map.get(id);
		if (null == images) return null;
		
		for (int i=level; i<images.length; i++) {
			if (null != images[i]) return images[i];
		}
		return null;
	}

	/** Find the cached image of the given level or its closest but smaller image (larger level), or null if none found. */
	public final Image getClosestBelow(final long id, final int level) {
		if (0 == LINEAR_SEARCH_LIMIT) return getBelowFromMap(id, level);
		Entry ee = null;
		int lev = Integer.MAX_VALUE;
		final ListIterator<Entry> li = cache.listIterator(cache.size());
		int i = 0;
		while (li.hasPrevious()) { // images are more likely to be close to the end
			if (i > LINEAR_SEARCH_LIMIT) {
				return getBelowFromMap(id, level);
			}
			i++;
			///
			final Entry e = li.previous();
			if (e.id != id) continue;
			if (e.level == level) return e.image;
			if (e.level > level) {
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
	}

	/** Find the cached image of the given level or its closest but larger image (smaller level), or null if none found. */
	public final Image getClosestAbove(final long id, final int level) {
		if (0 == LINEAR_SEARCH_LIMIT) return getAboveFromMap(id, level);
		int lev = -1;
		Entry ee = null;
		final ListIterator<Entry> li = cache.listIterator(cache.size());
		int i = 0;
		while (li.hasPrevious()) { // images are more likely to be close to the end
			if (i > LINEAR_SEARCH_LIMIT) {
				return getAboveFromMap(id, level);
			}
			i++;
			final Entry e = li.previous();
			if (e.id != id) continue;
			// if equal level as asked, just return it
			if (e.level == level) return e.image;
			// if exactly one above, just return it // may hinder finding an exact one, but potentially cuts down search time a lot
			// if (e.level == level + 1) return e.image; // WOULD NOT BE THE PERFECT IMAGE if the actual asked level is cached at a previous entry.
			if (e.level < level) {
				if (e.level > lev) {
					lev = e.level;
					ee = e;
				}
			}
		}
		if (null != ee) {
			cache.remove(ee);
			cache.addLast(ee);
			return ee.image;
		}
		return null;
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
		if (use_map) {
			final Image[] images = map.get(id);
			if (images == null) return null;
			images[level] = null;
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
		if (use_map) map.clear();
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
		if (use_map) {
			final Iterator<Map.Entry<Long,Image[]>> it = map.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<Long,Image[]> entry = it.next();
				final Image[] images = entry.getValue();
				if (null == images[0]) it.remove();
				for (int i=1; i<images.length; i++) {
					images[i] = null;
				}
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
		if (use_map) {
			final Image[] images = map.get(id);
			if (null != images) {
				for (int i=1; i<images.length; i++) {
					images[i] = null;
				}
				if (null == images[0]) map.remove(id);
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
		if (use_map) {
			map.remove(id);
		}
		return al;
	}

	/** Returns a table of level keys and image values that share the same id (that is, belong to the same Patch). */
	public final Hashtable<Integer,Image> getAll(final long id) {
		final Hashtable<Integer,Image> ht = new Hashtable<Integer,Image>();
		if (use_map) {
			final Image[] images = map.get(id);
			if (null != images) {
				for (int i=0; i<images.length; i++) {
					if (null != images[i]) {
						ht.put(i, images[i]);
					}
				}
			}
		} else {
			final ListIterator<Entry> li = cache.listIterator(cache.size());
			while (li.hasPrevious()) {
				final Entry e = li.previous();
				if (id == e.id) ht.put(e.level, e.image);
			}
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
		if (use_map) map.remove(id);
	}

	/** Remove the given index and return it, returns null if outside range. */
	public final Image remove(int i) {
		if (i < 0 || i >= cache.size()) return null;
		final Entry e = cache.remove(i);
		if (use_map) {
			nullifyMap(e.id, e.level);
		}
		return e.image;
	}

	private final void nullifyMap(final long id, final int level) {
		final Image[] images = map.get(id);
		if (null != images) {
			images[level] = null;
			for (Image im : images) {
				if (null != im) return;
			}
			// all null, remove
			map.remove(id);
		}
	}

	/** Remove the first element and return it. Returns null if none. The underlaying arrays are untouched besides nullifying the proper pointer. */
	public final Image removeFirst() {
		final Entry e = cache.removeFirst();
		if (use_map) {
			nullifyMap(e.id, e.level);
		}
		return e.image;
	}

	/** Checks if there's any image at all for the given id. */
	public final boolean contains(final long id) {
		if (use_map) {
			return map.containsKey(id);
		} else {
			final ListIterator<Entry> li = cache.listIterator(cache.size());
			while (li.hasPrevious()) {
				final Entry e = li.previous();
				if (id == e.id) return true;
			}
		}
		return false;
	}

	/** Checks if there's any image for the given id and level. */
	public final boolean contains(final long id, final int level) {
		if (use_map) {
			final Image[] images = map.get(id);
			if (null == images) return false;
			return level < images.length ? null != images[level] : false;
		}
		return -1 != cache.lastIndexOf(new Entry(id, level, null));
	}

	public int size() { return cache.size(); }

	public void debug() {
		System.out.println("cache size: " + cache.size() + ", " + map.size());
	}

	/** Does nothing. */
	public void gc() {}
}

