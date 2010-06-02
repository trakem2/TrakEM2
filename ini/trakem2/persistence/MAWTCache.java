package ini.trakem2.persistence;

import ini.trakem2.utils.Utils;

import java.awt.Image;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;

/** Access is not synchronized, that is your duty.
 * 
 *  The current setup depends on calls to removeAndFlushSome to clean up empty slots;
 *  otherwise these slots are never cleaned up to avoid O(n) overhead (worst case)
 *  when removing a Pyramid for a given id, or O(1) cost of checking whether the first interval
 *  is empty and removing it. Granted, the latter could be done in all calls to @method append,
 *  but in the current setup this overhead is just not necessary. */
public class MAWTCache implements ImageCache {
	
	private final class Pyramid {
		private final Image[] images;
		private HashMap<Long,Pyramid> interval = null;
		private final long id;
		private int n_images;

		Pyramid(final long id, final Image image, final int level) {
			this.id = id;
			this.images = new Image[maxLevel(image, level)];
			this.images[level] = image;
			this.n_images = 1;
		}

		/** Accepts a null @param img. */
		final void replace(final Image img, final int level) {
			if (null == this.images[level]) {
				this.images[level] = img;
				this.n_images++;
			} else {
				this.images[level].flush();
				this.images[level] = img;
				if (null == img) this.n_images--;
			}
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
		/*
		final int w = image.getWidth(null);
		final int h = image.getHeight(null);
		int max_level = starting_level;

		while (w > 32 || h > 32) {
			w /= 2;
			h /= 2;
			max_level++;
		}
		return max_level;
		*/

		final int max = Math.max(image.getWidth(null), image.getHeight(null));
		return starting_level + (max < max_levels.length ? max_levels[max] : computeLevel(max));

		/*
		if (max >= max_levels.length) {
			return starting_level + computeLevel(max);
		} else {
			return starting_level + max_levels[max];
		}
		*/
	}
	
	private final HashMap<Long,Pyramid> pyramids = new HashMap<Long,Pyramid>();
	private final LinkedList<HashMap<Long,Pyramid>> intervals = new LinkedList<HashMap<Long,Pyramid>>();
	private int count = 0;
	
	public MAWTCache() {}
	
	public final boolean contains(final long id) {
		return pyramids.containsKey(id);
	}

	public final boolean contains(final long id, final int level) {
		final Pyramid p = pyramids.get(id);
		return null != p && null != p.images[level];
	}

	public final Image get(final long id, final int level) {
		final Pyramid p = pyramids.get(id);
		if (null == p) return null;
		if (null == p || null == p.images[level]) return null;
		
		update(p);
		
		return p.images[level];
	}

	public final Map<Integer,Image> getAll(final long id) {
		final Pyramid p = pyramids.get(id);
		final HashMap<Integer,Image> m = new HashMap<Integer,Image>();
		if (null == p) return m;
		for (int i=0; i<p.images.length; i++) {
			if (null != p.images[i]) m.put(i, p.images[i]);
		}
		update(p);
		return m;
	}

	public final Image getClosestAbove(final long id, final int level) {
		final Pyramid p = pyramids.get(id);
		if (null == p) return null;
		for (int i=Math.min(level, p.images.length-1); i>-1; i--) {
			if (null == p.images[i]) continue;
			update(p);
			return p.images[i];
		}
		return null;
	}

	// Below or equal
	public final Image getClosestBelow(final long id, final int level) {
		final Pyramid p = pyramids.get(id);
		if (null == p) return null;
		for (int i=level; i<p.images.length; i++) {
			if (null == p.images[i]) continue;
			update(p);
			return p.images[i];
		}
		return null;
	}

	static private final int MAX_INTERVAL_SIZE = 20;
	private HashMap<Long,Pyramid> last_interval = new HashMap<Long,Pyramid>(MAX_INTERVAL_SIZE);
	{
		intervals.add(last_interval);
	}
	
	private final void reset() {
		pyramids.clear();
		intervals.clear();
		count = 0;
		last_interval = new HashMap<Long, Pyramid>(MAX_INTERVAL_SIZE);
		intervals.add(last_interval);
	}
	
	private final void update(final Pyramid p) {
		// Last-access -based priority queue:
		// Remove from current interval and append to last interval
		if (last_interval != p.interval) {
			p.interval.remove(p.id);
			append(p);
		}
	}

	/** Append the key to the last interval, creating a new interval if the last is full.
	 *  Then set that interval as the key's interval. */
	private final void append(final Pyramid p) {
		if (last_interval.size() >= MAX_INTERVAL_SIZE) {
			last_interval = new HashMap<Long,Pyramid>(MAX_INTERVAL_SIZE);
			intervals.add(last_interval);
		}
		last_interval.put(p.id, p);
		// Reflection: tell the Pyramid instance where it is
		p.interval = last_interval;
	}

	// If already there, move to latest interval
	// If the image is different, flush the old image
	public final void put(final long id, final Image image, final int level) {
		Pyramid p = pyramids.get(id);
		if (null == p) {
			p = new Pyramid(id, image, level);
			pyramids.put(id, p);
			append(p);
		} else {
			update(p);
			p.replace(image, level);
		}
		count++;
	}

	// WARNING: an empty interval may be left behind. Will be cleaned up by removeAndFlushSome.
	public final Image remove(final long id, final int level) {
		final Pyramid p = pyramids.get(id);
		if (null == p) return null;
		final Image im = p.images[level];
		p.replace(null, level);
		count--;
		// If at least one level is still not null, keep the pyramid; otherwise drop it
		if (0 == p.n_images) {
			p.interval.remove(id);
			pyramids.remove(id);
		}
		return im;
	}

	public final void removeAndFlushAll() {
		for (final Pyramid p : pyramids.values()) {
			for (int i=0; i<p.images.length; i++) {
				if (null == p.images[i]) continue;
				p.images[i].flush();
			}
		}
		reset();
	}

	// WARNING: an empty interval may be left behind. Will be cleaned up by removeAndFlushSome.
	public final void removeAndFlush(final long id) {
		final Pyramid p = pyramids.remove(id);
		if (null == p) return;
		for (int i=0; i<p.images.length; i++) {
			if (null != p.images[i]) {
				p.images[i].flush();
				count--;
			}
		}
		p.interval.remove(id);
	}
	
	/** Returns the number of released bytes. */
	public final long removeAndFlushSome(final long min_bytes) {
		long size = 0;
		while (intervals.size() > 0) {
			final HashMap<Long,Pyramid> interval = intervals.getFirst();
			for (final Iterator<Pyramid> it = interval.values().iterator(); it.hasNext(); ) {
				final Pyramid p = it.next();
				for (int i=0; i<p.images.length; i++) {
					if (null == p.images[i]) continue;
					size += Loader.measureSize(p.images[i]);
					p.replace(null, i);
					count--;
					if (size >= min_bytes) {
						if (0 == p.n_images) {
							pyramids.remove(p.id);
							it.remove();
							if (interval.isEmpty()) intervals.removeFirst();
						}
						return size;
					}
				}
				pyramids.remove(p.id);
				it.remove(); // from the interval
			}
			intervals.removeFirst();
		}
		return size;
	}

	public final long removeAndFlushSome(int n) {
		long size = 0;
		while (intervals.size() > 0) {
			final HashMap<Long,Pyramid> interval = intervals.getFirst();
			for (final Iterator<Pyramid> it = interval.values().iterator(); it.hasNext(); ) {
				final Pyramid p = it.next();
				for (int i=0; i<p.images.length; i++) {
					if (null == p.images[i]) continue;
					size += Loader.measureSize(p.images[i]);
					p.replace(null, i);
					n--;
					count--;
					if (0 == n) {
						if (0 == p.n_images) {
							pyramids.remove(p.id);
							it.remove();
							if (interval.isEmpty()) intervals.removeFirst();
						}
						return size;
					}
				}
				pyramids.remove(p.id);
				it.remove(); // from the interval
			}
			intervals.removeFirst();
		}
		return size;
	}

	public int size() {
		return count;
	}
	
	public void debug() {
		Utils.log2("@@@@@@@@@@ START");
		Utils.log2("pyramids: " + pyramids.size());
		for (Map.Entry<Long,Pyramid> e : new TreeMap<Long,Pyramid>(pyramids).entrySet()) {
			Utils.log2("p id:" + e.getKey() + " value: " + e.getValue().images.length);
		}
		Utils.log2("----");
		int i = 0;
		for (HashMap<Long,Pyramid> m : intervals) {
			Utils.log2("interval " + (++i));
			for (Map.Entry<Long,Pyramid> e : new TreeMap<Long,Pyramid>(m).entrySet()) {
				Utils.log2("p id:" + e.getKey() + " value: " + e.getValue().images.length);
			}
		}
		Utils.log2("----");
		// Analytics
		Utils.log2("count is: " + count + ", intervals.size = " + intervals.size() + ", pyr.size = " + pyramids.size());
		HashMap<Integer,Integer> s = new HashMap<Integer,Integer>();
		for (HashMap<Long,Pyramid> m : intervals) {
			int l = m.size();
			Integer in = s.get(l);
			if (null == in) s.put(l, 1);
			else s.put(l, in.intValue() + 1);
		}
		Utils.log2("interval size distribution: ", s);
	}
}
