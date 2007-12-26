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
import java.awt.Image;

public class FIFOImageMipMaps {

	private int start;
	private int next;
	private long[] ids;
	private Image[] images;
	private int[] levels;
	private final int inc = 50;

	public FIFOImageMipMaps(int initial_size) {
		reset(initial_size);
	}

	private final void reset(int initial_size) {
		if (initial_size < 0) initial_size = inc;
		this.images = new Image[initial_size];
		this.ids = new long[initial_size];
		this.levels = new int[initial_size];
		start = 0;
		next = 0;
	}

	/** Replace or put. */
	public void replace(final long id, final Image image, final int level) {
		try {
			if (null == image) throw new Exception("FIFOImageMap: null image!");
		} catch (Exception e) {
			new IJError(e);
			return;
		}
		for (int i=start; i<next; i++) {
			if (id == ids[i] && level == levels[i]) {
				images[i].flush();
				images[i] = image;
				return;
			}
		}
		// else, put (should not happen ever)
		put(id, image, level);
	}

	/** No duplicates allowed: if the id exists it's sended to the end and the image is first flushed (if different), then updated with the new one provided. */
	public void put(final long id, final Image image, final int level) {

		try {
			if (null == image) throw new Exception("FIFOImageMap: null image!");
		} catch (Exception e) {
			new IJError(e);
			return;
		}
		// check if exists already, if so, send it to the end
		for (int i=start; i<next; i++) {
			if (id == ids[i] && level == levels[i]) {
				Image im = images[i];
				// put the found id at the end, move the others forward.
				next--;
				while (i < next) {
					ids[i] = ids[i+1];
					images[i] = images[i+1];
					levels[i] = levels[i+1];
					i++;
				}
				next++;
				ids[i] = id;
				if (im != null && !image.equals(im)) im.flush();
				images[i] = image;
				levels[i] = level;
				return;
			}
		}
		// clean up empty entries at the beggining
		if (0 != start) {
			next -= start;
			for (int i=0; i<next; i++) {
				ids[i] = ids[start + i];
				images[i] = images[start + i];
				levels[i] = levels[start + i];
			}
			start = 0;
		}
		// adjust arrays
		if (ids.length == next) {
			// enlarge arrays
			long[] ids2 = new long[ids.length + inc];
			Image[] images2 = new Image[ids2.length];
			int[] levels2 = new int[ids2.length];
			System.arraycopy(ids, 0, ids2, 0, ids.length);
			System.arraycopy(images, 0, images2, 0, images.length);
			System.arraycopy(levels, 0, levels2, 0, levels.length);
			ids = ids2;
			images = images2;
			levels = levels2;
		} else if (ids.length - 2*inc == next) {
			// shorten arrays if unnecessarily long
			long[] ids2 = new long[ids.length - inc]; // leave 'inc' slots at the end, so arrays need not be resized immediately in the next call to put
			Image[] images2 = new Image[ids2.length];
			int[] levels2 = new int[ids2.length];
			System.arraycopy(ids, 0, ids2, 0, ids.length - 2*inc+1);
			System.arraycopy(images, 0, images2, 0, images.length - 2*inc+1);
			System.arraycopy(levels, 0, levels2, 0, levels.length - 2*inc+1);
			ids = ids2;
			images = images2;
			levels = levels2;
		}
		// add at the end
		images[next] = image;
		ids[next] = id;
		levels[next] = level;
		next++;
	}

	/** A call to this method puts the element at the end of the list, and returns it. Returns null if not found. */
	public Image get(final long id, final int level) {
		// find the id
		int i = start;
		for (; i<next; i++) {
			if (id == ids[i] && level == levels[i]) {
				break;
			}
		}
		if (i == next) return null; // not found
		Image im = images[i];
		// put the found id at the end, move the others forward.
		next--;
		while (i < next) {
			ids[i] = ids[i+1];
			images[i] = images[i+1];
			levels[i] = levels[i+1];
			i++;
		}
		next++;
		ids[i] = id; // it's the same one, otherwise it would not have been found
		images[i] = im;
		levels[i] = level; // it's the same one, otherwise it would not have been found
		return im;
	}

	private final void toTheEnd(final int index) {
		Image im = images[index];
		int level = levels[index];
		long id = ids[index];
		// put the found id at the end, move the others forward.
		next--;
		int i = index;
		while (i < next) {
			ids[i] = ids[i+1];
			images[i] = images[i+1];
			levels[i] = levels[i+1];
			i++;
		}
		next++;
		ids[i] = id;
		images[i] = im;
		levels[i] = level;
	}

	/** Find the cached image of the given level or its closest but smaller one, or null if none found. */
	public Image getClosestBelow(final long id, final int level) {
		Image im = null;
		int lev = Integer.MAX_VALUE;
		int index = -1;
		for (int i=start; i<next; i++) {
			if (id == ids[i]) {
				/*
				if (level == levels[i]) {
					// if equal level as asked, just return it
					im = images[i];
					toTheEnd(i);
					return im;
				} else
				*/
				if (levels[i] > level) {
					// if smaller image (larger level) than asked, choose the less smaller
					if (levels[i] < lev) { // here going for the largest image (smaller level) that is still smaller, and thus its level larger, than the desired level
						lev = levels[i];
						index = i;
						im = images[i];
					}
				}
			}
		}
		if (-1 != index) toTheEnd(index);
		return im;
	}

	/** Find the cached image of the given level or its closest but larger one, or null if none found. */
	public Image getClosestAbove(final long id, final int level) {
		Image im = null;
		int lev = -1;
		int index = -1;
		for (int i=start; i<next; i++) {
			if (id == ids[i]) {
				if (level == levels[i]) {
					// if equal level as asked, just return it
					im = images[i];
					toTheEnd(i);
					return im;
				} else if (levels[i] < level) {
					// if larger image (smaller level) than asked, choose the less larger
					if (levels[i] > lev) { // here going for the smallest image (larger level) that is still larger, and thus its level smaller, than the desired level
						lev = levels[i];
						index = i;
						im = images[i];
					}
				}
			}
		}
		if (-1 != index) toTheEnd(index);
		return im;
	}

	/** Remove the Image if found and returns it, without flushing it. Returns null if not found. */
	public Image remove(final long id, final int level) {
		// find the id
		int i = start;
		for (; i<next; i++) {
			if (id == ids[i] && level == levels[i]) {
				break;
			}
		}
		if (i == next) return null;
		Image im = images[i];
		// move the others forward.
		next--;
		while (i < next) {
			ids[i] = ids[i+1];
			images[i] = images[i+1];
			levels[i] = levels[i+1];
			i++;
		}
		return im;
	}

	/** Removes and flushes all images, and shrinks arrays. */
	public void removeAndFlushAll() {
		for (int i=start; i<next; i++) {
			images[i].flush();
		}
		reset(0);
	}

	/** Remove all awts associated with a level different than 0 (that means all scaled down versions) for any id. */
	public void removeAllPyramids() {
		int end = next;
		for (int i=start+1; i<end; i++) {
			if (i == next) break;
			if (0 != levels[i]) {
				Image awt = remove(i); // may modify start and/or next
				awt.flush();
				if (i != start) { // start may keep moving forward
					end--;
					i--;
				}
			}
		}
	}

	/** Remove all awts associated with a level different than 0 (that means all scaled down versions) for the given id. */
	public void removePyramid(final long id) {
		int end = next;
		for (int i=start+1; i<end; i++) {
			if (i == next) break;
			if (0 != levels[i] && id == ids[i]) {
				Image awt = remove(i); // may modify start and/or next
				awt.flush();
				if (i != start) { // start may keep moving forward
					end--;
					i--;
				}
			}
		}
	}

	/** Remove all images that share the same id (but have different levels). */
	public ArrayList<Image> remove(final long id) {
		final ArrayList<Image> al = new ArrayList<Image>();
		int i = start;
		for (; i<next; i++) {
			if (id == ids[i]) {
				al.add(images[i]);
				// move the others to close the gap
				next--;
				for (int j=i; j<next; j++) {
					ids[j] = ids[j+1];
					images[j] = images[j+1];
					levels[j] = levels[j+1];
				}
			}
		}
		return al;
	}

	public Hashtable<Integer,Image> getAll(final long id) {
		final Hashtable<Integer,Image> ht = new Hashtable<Integer,Image>();
		int i = start;
		for (; i<next; i++) {
			if (id == ids[i]) {
				ht.put(levels[i], images[i]);
			}
		}
		return ht;
	}

	/** Remove and flush away all images that share the same id. */
	public void removeAndFlush(final long id) {
		for (int i = start; i<next; i++) {
			if (id == ids[i]) {
				if (null != images[i]) images[i].flush();
				// move the others to close the gap
				next--;
				for (int j=i; j<next; j++) {
					ids[j] = ids[j+1];
					images[j] = images[j+1];
					levels[j] = levels[j+1];
				}
			}
		}
	}

	/** Remove the given index and return it, returns null if outside range. The underlying arrays are untouched besides nullifying the proper pointer if the given 'i' is the first element of the arrays. */
	public Image remove(int i) {
		if (i < start || i >= next) return null;
		Image im = images[i];
		if (i == start) {
			start++;
			images[i] = null;
		} else if (i == next -1) {
			next--;
			images[i] = null;
		} else {
			// move the others forward.
			next--;
			while (i < next) {
				ids[i] = ids[i+1];
				images[i] = images[i+1];
				levels[i] = levels[i+1];
				i++;
			}
		}
		return im;
	}

	/** Remove the first element and return it. Returns null if none. The underlaying arrays are untouched besides nullifying the proper pointer. */
	public Image removeFirst() {
		if (start == next) return null; //empty!
		Image im = images[start];
		images[start] = null;
		start++;
		return im;
	}

	/** Checks if there's any image at all for the given id. */
	public boolean contains(final long id) {
		for (int i=0; i<next; i++) {
			if (id == ids[i]) return true;
		}
		return false;
	}

	/** Checks if there's any image for the given id and level. */
	public boolean contains(final long id, final int level) {
		for (int i=0; i<next; i++) {
			if (id == ids[i] && level == levels[i]) return true;
		}
		return false;
	}

	public int size() { return next - start; }

	public void debug() {
		for (int i=0; i<next; i++) {
			System.out.println(i + " id: " + ids[i] + " level: " + levels[i]);
		}
	}

}

