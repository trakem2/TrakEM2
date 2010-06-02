package ini.trakem2.persistence;

import java.awt.Image;
import java.util.ArrayList;
import java.util.Map;

public interface ImageCache {

	/** No duplicates allowed: if the id exists it's sent to the end and the image is first flushed (if different), then updated with the new one provided. */
	public abstract void put(final long id, final Image image, final int level);

	/** A call to this method puts the element at the end of the list, and returns it. Returns null if not found. */
	public abstract Image get(final long id, final int level);

	/** Find the cached image of the given level or its closest but smaller image (larger level), or null if none found. */
	public abstract Image getClosestBelow(final long id, final int level);

	/** Find the cached image of the given level or its closest but larger image (smaller level), or null if none found. */
	public abstract Image getClosestAbove(final long id, final int level);

	/** Remove the Image if found and returns it, without flushing it. Returns null if not found. */
	public abstract Image remove(final long id, final int level);

	/** Removes and flushes all images, and shrinks arrays. */
	public abstract void removeAndFlushAll();

	/** Returns a table of level keys and image values that share the same id (that is, belong to the same Patch). */
	public abstract Map<Integer, Image> getAll(final long id);
	
	/** Remove and flush away up to n images, and return the size in bytes of all flushed. */
	public abstract long removeAndFlushSome(final int n);
	
	/** Remove and flush away n_bytes or more, and return the size in bytes of all flushed. */
	public abstract long removeAndFlushSome(final long n_bytes);

	/** Checks if there's any image at all for the given id. */
	public abstract boolean contains(final long id);

	/** Checks if there's any image for the given id and level. */
	public abstract boolean contains(final long id, final int level);

	public abstract int size();

}