/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005, 2006 Albert Cardona and Rodney Douglas.

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

import java.awt.Image;

public class FIFOImageMap {

	private int start;
	private int next;
	private long[] ids;
	private Image[] images;
	private final int inc = 50;

	public FIFOImageMap(int initial_size) {
		if (initial_size < 0) initial_size = inc;
		this.images = new Image[initial_size];
		this.ids = new long[initial_size];
		start = 0;
		next = 0;
	}

	/** No duplicates allowed: if the id exists it's sended to the end and the image is first flushed (if different), then updated with the new one provided. */
	public void put(long id, Image image) {

		try {
			if (null == image) throw new Exception("FIFOImageMap: null image!");
		} catch (Exception e) {
			new IJError(e);
			return;
		}
		// check if exists already, if so, send it to the end
		for (int i=start; i<next; i++) {
			if (id == ids[i]) {
				long idd = ids[i];
				Image im = images[i];
				// put the found id at the end, move the others forward.
				next--;
				while (i < next) {
					ids[i] = ids[i+1];
					images[i] = images[i+1];
					i++;
				}
				next++;
				ids[i] = idd;
				if (im != null && !image.equals(im)) im.flush();
				images[i] = image;
				return;
			}
		}
		// clean up empty entries at the beggining
		if (0 != start) {
			next -= start;
			for (int i=0; i<next; i++) {
				ids[i] = ids[start + i];
				images[i] = images[start + i];
			}
			start = 0;
		}
		// adjust arrays
		if (ids.length == next) {
			// enlarge arrays
			long[] ids2 = new long[ids.length + inc];
			Image[] images2 = new Image[ids.length + inc];
			System.arraycopy(ids, 0, ids2, 0, ids.length);
			System.arraycopy(images, 0, images2, 0, images.length);
			ids = ids2;
			images = images2;
		} else if (ids.length - 2*inc == next) {
			// shorten arrays if unnecessarily long
			long[] ids2 = new long[ids.length - inc]; // leave 'inc' slots at the end, so arrays need not be resized immediately in the next call to put
			Image[] images2 = new Image[ids.length - inc];
			System.arraycopy(ids, 0, ids2, 0, ids.length - 2*inc+1);
			System.arraycopy(images, 0, images2, 0, images.length - 2*inc+1);
			ids = ids2;
			images = images2;
		}
		// add
		images[next] = image;
		ids[next] = id;
		next++;
	}

	/** A call to this method puts the element at the end of the list, and returns it. Returns null if not found. */
	public Image get(long id) {
		// find the id
		long idd = -1L;
		int i = start;
		for (; i<next; i++) {
			if (ids[i] == id) {
				idd = ids[i];
				break;
			}
		}
		if (i == next) return null;
		Image im = images[i];
		// put the found id at the end, move the others forward.
		next--;
		while (i < next) {
			ids[i] = ids[i+1];
			images[i] = images[i+1];
			i++;
		}
		next++;
		ids[i] = idd;
		images[i] = im;
		return im;
	}

	/** Remove the Image if found and returns it, without flushing it. Returns null if not found. */
	public Image remove(long id) {
		// find the id
		int i = start;
		for (; i<next; i++) {
			if (ids[i] == id) {
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
			i++;
		}
		return im;
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

	public boolean contains(long id) {
		for (int i=0; i<next; i++) {
			if (id == ids[i]) return true;
		}
		return false;
	}

	public int size() { return next - start; }

	public void debug() {
		for (int i=0; i<next; i++) {
			System.out.println(i + " id: " + ids[i]);
		}
	}

}
