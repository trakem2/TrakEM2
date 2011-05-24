package ini.trakem2.persistence;

import ij.ImagePlus;
import ij.io.FileInfo;

import java.awt.Image;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeSet;

/** Unsynchronized, that is your duty. */
final class Cache
{
	private class Holder<T> {
		/** Point to the actual data so that this instance has weight. */
		@SuppressWarnings("unused")
		final T t;
		Holder(final T t) {
			this.t = t;
		}
	}

	private abstract class CacheReference<T> extends SoftReference<Holder<T>> {
		/** Reference to the actual data. The super constructor
		 * is given a new Holder that also points to the data.
		 * When the Holder is only softly reachable (which happens immediately)
		 * and there is need for freeing space, then this reference is enqueued. */
		protected final T t;
		protected CacheReference(final T t, final ReferenceQueue<Holder<?>> q) {
			super(new Holder<T>(t), q);
			this.t = t;
		}
		abstract void cleanup();
		abstract void flush();
	}

	private final class ImageReference extends CacheReference<Image> {
		private final long id;
		private final int level;
		private ImageReference(final long id, final Image img, final int level) {
			super(img, queue);
			this.id = id;
			this.level = level;
		}
		@Override
		final void cleanup() {
			final Map<Long,ImageReference> m = awts.get(level);
			if (null == m) return;
			if (this == m.get(id)) m.remove(id); // otherwise it's a new one, was reset.
			flush();
		}
		@Override
		final void flush() {
			t.flush();
		}
	}
	
	private final class ImagePlusReference extends CacheReference<ImagePlus> {
		private final long id;
		private ImagePlusReference(final long id, final ImagePlus imp) {
			super(imp, queue);
			this.id = id;
		}
		@Override
		final void cleanup() {
			if (this == imps.get(id)) imps.remove(id); // otherwise it's a new one, was reset
			final String path = getPath(t);
			if (null == path) return;
			if (this == impsByPath.get(path)) impsByPath.remove(path); // as above
		}
		@Override
		final void flush() {
			final Image awt = t.getImage();
			if (null != awt) awt.flush();
		}
	}

	public final void cleanup() {
		CacheReference<?> cr;
		while (null != (cr = (CacheReference<?>) queue.poll())) {
			cr.cleanup();
		}
	}

	/** Gets CacheReference instances with an Image or an ImagePlus. */
	private final ReferenceQueue<Holder<?>> queue = new ReferenceQueue<Holder<?>>();

	private final Thread queueManager;

	/** Takes the lock of the Loader, that also locks access to all methods. */
	Cache(final Object lock) {
		this.queueManager = new Thread() {
			{ setPriority(Thread.NORM_PRIORITY + 1); }
			public void run() {
				try {
					while (true) {
						final CacheReference<?> cr = (CacheReference<?>) queue.remove();
						System.out.println("@@@ queueManager run");
						synchronized (lock) {
							cr.cleanup();
						}
					}
				} catch (InterruptedException ie) {}
			}
		};
		this.queueManager.start();
	}

	private final Map<Integer,Map<Long,ImageReference>> awts = new HashMap<Integer,Map<Long,ImageReference>>();
	private final Map<Long,ImagePlusReference> imps = new HashMap<Long,ImagePlusReference>();
	private final Map<String,ImagePlusReference> impsByPath = new HashMap<String,ImagePlusReference>();
	private final TreeSet<Integer> levels = new TreeSet<Integer>();
	
	public final boolean contains(final long id) {
		for (final Map<Long,?> m : awts.values()) {
			if (m.containsKey(id)) return true;
		}
		return false;
	}
	public final boolean contains(final long id, final int level) {
		final Map<Long,?> m = awts.get(level);
		if (null == m) return false;
		return m.containsKey(id);
	}
	public final Image get(final long id, final int level) {
		final Map<Long,ImageReference> m = awts.get(level);
		if (null == m) return null;
		final ImageReference sr = m.get(id);
		if (null == sr) return null;
		final Image im = sr.t;
		if (null == im) return null;
		// Store again, to reset its timer
		m.put(id, new ImageReference(id, im, level));
		return im;
	}
	public final Image getClosestAbove(final long id, int level) {
		while (level > -1) {
			final Map<Long,ImageReference> m = awts.get(level);
			level -= 1;
			if (null == m) continue;
			final ImageReference sr = m.get(id);
			if (null == sr) continue;
			return sr.t;
		}
		return null;
	}
	public final Image getClosestBelow(final long id, int level) {
		final Integer above = levels.higher(level);
		if (null == above) return null;
		final Map<Long,ImageReference> m = awts.get(above);
		if (null == m) return null;
		final ImageReference sr = m.get(id);
		if (null == sr) return null;
		return sr.t;
	}
	public final ImagePlus get(final long id) {
		final CacheReference<ImagePlus> sr = imps.get(id);
		if (null == sr) return null;
		return sr.t;
	}
	public final ImagePlus get(final String path) {
		final ImagePlusReference sr = impsByPath.get(path);
		if (null == sr) return null;
		// Store again, to reset its timer
		impsByPath.put(path, new ImagePlusReference(sr.id, sr.t));
		return sr.t;
	}
	public final void put(final long id, final Image image, final int level) {
		Map<Long,ImageReference> m = awts.get(level);
		if (null == m) {
			m = new HashMap<Long,ImageReference>();
			awts.put(level, m);
			levels.add(level);
		}
		m.put(id, new ImageReference(id, image, level));
	}
	public final void updateImagePlusPath(final String oldPath, final String newPath) {
		final ImagePlusReference sr = impsByPath.remove(oldPath);
		if (null == sr) return;
		impsByPath.put(newPath, new ImagePlusReference(sr.id, sr.t));
	}
	/** Returns null if the ImagePlus was preprocessed or doesn't have an original FileInfo
	* (which means the image does not come from a file). */
	static public final String getPath(final ImagePlus imp) {
		final FileInfo fi = imp.getOriginalFileInfo();
		if (null == fi || Loader.PREPROCESSED == fi.fileFormat) return null;
		final String dir = fi.directory;
		if (null == dir) {
			return fi.url;
		}
		return dir + fi.fileName;
	}
	public final void put(final long id, final ImagePlus imp) {
		final ImagePlusReference sr = new ImagePlusReference(id, imp);
		imps.put(id, sr);
		final String path = getPath(imp);
		if (null == path) return;
		impsByPath.put(path, sr);
	}
	public final Image remove(final long id, final Integer level) {
		final Map<Long,ImageReference> m = awts.get(level);
		if (null == m) return null;
		final ImageReference sr = m.remove(id);
		if (null == sr) return null;
		return sr.t;
	}
	public final ImagePlus removeImagePlus(final long id) {
		final CacheReference<ImagePlus> sr = imps.remove(id);
		if (null == sr) return null;
		final String path = getPath(sr.t);
		if (null == path) return null;
		imps.remove(path);
		return sr.t;
	}
	public final void remove(final long id) {
		removeAndFlushPyramid(id);
		removeImagePlus(id);
	}
	public final void removeAndFlushPyramid(final long id) {
		for (final Map<Long,ImageReference> m : awts.values()) {
			m.remove(id);
		}
	}
	public final void removeAndFlushAll() {
		for (final Iterator<Map<Long,ImageReference>> l = awts.values().iterator(); l.hasNext(); ) {
			for (final ImageReference sr : l.next().values()) {
				sr.clear(); // clear its referent, so it doesn't get enqueued
				sr.flush();
			}
		}
		awts.clear();
		imps.clear();
		impsByPath.clear();
		levels.clear();
	}
	public final long seqFindId(final ImagePlus imp) {
		for (final Map.Entry<Long,ImagePlusReference> e : imps.entrySet()) {
			if (imp == e.getValue().t) return e.getKey();
		}
		return Long.MIN_VALUE;
	}
	public final int count() {
		int count = 0;
		for (final Map<?,?> m : awts.values()) {
			count += m.size();
		}
		return count;
	}
	public void debug() {
		System.out.println("levels: " + awts.size());
		System.out.println("awts:");
		int total = 0;
		int totalAlive = 0;
		for (Map.Entry<Integer,Map<Long,ImageReference>> e : awts.entrySet()) {
			int alive = 0;
			for (ImageReference ir : e.getValue().values()) {
				if (ir.get() == null) continue;
				alive += 1;
			}
			totalAlive += alive;
			total += e.getValue().size();
			System.out.println("   " + e.getKey() + ": " + e.getValue().size() + ",  alive: " + alive);
		}
		System.out.println("awts total: " + total + " ,  alive: " + totalAlive);
		System.out.println("imps id: " + imps.size());
		System.out.println("imps path: " + impsByPath.size());
	}
}