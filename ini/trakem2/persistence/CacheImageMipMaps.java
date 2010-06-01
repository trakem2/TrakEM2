/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2008-2009 Albert Cardona and Stephan Preibisch.

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


import java.util.ArrayList;
import java.util.HashMap;
import java.util.ListIterator;
import java.util.LinkedList;
import java.util.Map;
import java.awt.Image;

/** A cache for TrakEM2's rolling memory of java.awt.Image instances.
 *  Uses both a list and a map.
 *  Each image is added as a CacheImageMipMaps.Entry, which stores the image, the corresponding Patch id and the level of the image (0, 1, 2 ... mipmap level of decreasing power of 2 sizes).
 *  The map contains unique Patch id keys versus Image arrays of values, where the array index is the index.
 */
public class CacheImageMipMaps implements ImageCache {

	private final LinkedList<Entry> cache;
	private final HashMap<Long,Image[]> map = new HashMap<Long,Image[]>();

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

	/* (non-Javadoc)
	 * @see ini.trakem2.persistence.ImageCache#replace(long, java.awt.Image, int)
	 */
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

	/* (non-Javadoc)
	 * @see ini.trakem2.persistence.ImageCache#put(long, java.awt.Image, int)
	 */
	public final void put(final long id, final Image image, final int level) {
		if (map.containsKey(id)) {
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
						Image[] images = map.get(id);
						if (null == images) images = new Image[maxLevel(image, level)];
						images[level] = image;
					}
					return;
				}
			}
		}
		// else, new
		cache.addLast(new Entry(id, level, image));

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
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.persistence.ImageCache#get(long, int)
	 */
	public final Image get(final long id, final int level) {
		return getFromMap(id, level);
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

	/* (non-Javadoc)
	 * @see ini.trakem2.persistence.ImageCache#getClosestBelow(long, int)
	 */
	public final Image getClosestBelow(final long id, final int level) {
		return getBelowFromMap(id, level);
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.persistence.ImageCache#getClosestAbove(long, int)
	 */
	public final Image getClosestAbove(final long id, final int level) {
		return getAboveFromMap(id, level);
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.persistence.ImageCache#remove(long, int)
	 */
	public final Image remove(final long id, final int level) {
		final Image[] images = map.get(id);
		if (null != images) {
			final ListIterator<Entry> li = cache.listIterator(cache.size());
			while (li.hasPrevious()) {
				final Entry e = li.previous();
				if (id == e.id && level == e.level) {
					li.remove();
					for (int i=0; i<images.length; i++) {
						if (null != images[i]) {
							images[level] = null; // == e.image
							return e.image;
						}
					}
					map.remove(id); // empty array
					return e.image;
				}
			}
		}
		return null;
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.persistence.ImageCache#removeAndFlushAll()
	 */
	public final void removeAndFlushAll() {
		final ListIterator<Entry> li = cache.listIterator(cache.size());
		while (li.hasPrevious()) {
			final Entry e = li.previous();
			e.image.flush();
		}
		cache.clear();
		map.clear();
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.persistence.ImageCache#remove(long)
	 */
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
		map.remove(id);
		return al;
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.persistence.ImageCache#getAll(long)
	 */
	public final Map<Integer,Image> getAll(final long id) {
		final Map<Integer,Image> ht = new HashMap<Integer,Image>();
		final Image[] images = map.get(id);
		if (null != images) {
			for (int i=0; i<images.length; i++) {
				if (null != images[i]) {
					ht.put(i, images[i]);
				}
			}
		}
		return ht;
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.persistence.ImageCache#removeAndFlush(long)
	 */
	public final void removeAndFlush(final long id) {
		if (null != map.remove(id)) {
			final ListIterator<Entry> li = cache.listIterator(cache.size());
			while (li.hasPrevious()) {
				final Entry e = li.previous();
				if (id == e.id) {
					e.image.flush();
					li.remove();
				}
			}
		}
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.persistence.ImageCache#remove(int)
	 */
	public final Image remove(final int i) {
		if (i < 0 || i >= cache.size()) return null;
		final Entry e = cache.remove(i);
		nullifyMap(e.id, e.level);
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

	/* (non-Javadoc)
	 * @see ini.trakem2.persistence.ImageCache#removeFirst()
	 */
	public final Image removeFirst() {
		if (0 == cache.size()) return null;
		final Entry e = cache.removeFirst();
		nullifyMap(e.id, e.level);
		return e.image;
	}
	
	public long removeAndFlushSome(final int n) {
		long size = 0;
		for (int i=Math.min(n, cache.size()); i>0; i--) {
			final Entry e = cache.removeFirst();
			size += Loader.measureSize(e.image);
			e.image.flush();
			nullifyMap(e.id, e.level);
		}
		return size;
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.persistence.ImageCache#contains(long)
	 */
	public final boolean contains(final long id) {
		return map.containsKey(id);
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.persistence.ImageCache#contains(long, int)
	 */
	public final boolean contains(final long id, final int level) {
		final Image[] images = map.get(id);
		if (null == images) return false;
		return level < images.length ? null != images[level] : false;
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.persistence.ImageCache#size()
	 */
	public int size() { return cache.size(); }

	public void debug() {
		System.out.println("cache size: " + cache.size() + ", " + map.size());
	}
}

