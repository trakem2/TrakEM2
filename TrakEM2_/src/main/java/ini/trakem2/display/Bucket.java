/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005-2009 Albert Cardona and Rodney Douglas.

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

package ini.trakem2.display;

import ini.trakem2.utils.M;
import ini.trakem2.utils.Utils;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;


/** 
 * A Bucket is a subarea of the Layer area, which contains either other Buckets or a map of stack_index vs. Displayable instances. VERY IMPORTANT: either children is null, or map is null, but both cannot be null at the same time neither not null at the same time.
 *
 */
public class Bucket {

	static public final int MIN_BUCKET_SIZE = 4096;

	private int bucket_side;

	/** The sorted map of stack_index and Displayable objects that are fully contained in this bucket or intersect at top and left, but not at right and bottom. That is, the lower-right corner of the Displayable is contained with the area of this bucket. */
	private TreeMap<Integer,Displayable> map = null;
	/** The set of sub-buckets contained here. */
	private ArrayList<Bucket> children = null;

	private final int x,y,w,h;

	private boolean empty = true;

	public Bucket(final int x, final int y, final int w, final int h, final int bucket_side) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		this.bucket_side = bucket_side;
		Utils.showStatus(new StringBuilder("Creating bucket ").append(x).append(',').append(y).append(',').append(w).append(',').append(h).toString(), false);
		//Utils.log2(this.toString());
	}

	public String toString() {
		return "Bucket: " + x + ",  " + y + ", " + w + ", " + h;
	}

	synchronized final void populate(final Bucketable container, final Layer layer, final HashMap<Displayable,HashSet<Bucket>> db_map) {
		// Reset
		if (null != this.map) this.map.clear();
		this.children = null;
		// Refill:
		final HashMap<Integer,Displayable> list = new HashMap<Integer,Displayable>();
		int i = 0;
		// cache all bounding boxes
		final HashMap<Displayable,Area> areas = new HashMap<Displayable,Area>();
		for (final Displayable d : container.getDisplayableList()) {
			list.put(i, d);
			i++;
			final Area a = d.getAreaForBucket(layer);
			if (null != a) areas.put(d, a);
		}
		populate(container, db_map, w+w, h+h, w, h, list, areas);
	}

	/** Recursive initialization of buckets. This method is meant to be used as init, when root is null or is made new from scratch. Returns true if not empty. */
	final private boolean populate(final Bucketable container, final HashMap<Displayable,HashSet<Bucket>> db_map, final int parent_w, final int parent_h, final int max_width, final int max_height, final HashMap<Integer,Displayable> parent_list, final HashMap<Displayable,Area> areas) {
		if (this.w <= bucket_side || this.h <= bucket_side) {
			// add displayables, sorted by index
			map = new TreeMap<Integer,Displayable>();
			for (final Map.Entry<Integer,Displayable> e : parent_list.entrySet()) {
				final Displayable d = e.getValue();
				final Area a = areas.get(d);
				if (null == a) continue;
				if (a.intersects(x, y, w, h)) {
					map.put(e.getKey(), d);
					putToBucketMap(d, db_map);
				}
			}
			this.empty = map.isEmpty();
			//Utils.log2(empty ? "EMPTY ": "FILLED " + this);
		} else {
			// create child buckets as subdivisions of this one
			children = new ArrayList<Bucket>(2*2);

			int side_w = (int)Math.pow(2, (int)Math.floor(Math.log(Math.max(w,h)) / Math.log(2)) - 1);
			int side_h = side_w;
			if (side_w > max_width) side_w = max_width;
			if (side_h > max_height) side_h = max_height;

			// create list of Displayables that will be added here, as extracted from the parent list
			final HashMap<Integer,Displayable> local_list = new HashMap<Integer,Displayable>();
			for (final Map.Entry<Integer,Displayable> e : parent_list.entrySet()) {
				final Displayable d = e.getValue();
				final Area a = areas.get(d);
				if (null == a) continue;
				if (a.intersects(x, y, w, h)) local_list.put(e.getKey(), d);
			}

			//Utils.log2(local_list.size() + " :: " + this.toString());

			for (int x=0; x<parent_w; x += side_w) {
				if (this.x + x >= max_width) continue;
				int width = side_w;
				if (this.x + x + side_w > max_width) width = max_width - this.x - x;
				for (int y=0; y<parent_h; y += side_h) {
					if (this.y + y >= max_height) continue;
					int height = side_h;
					if (this.y + y + side_h > max_height) height = max_height - this.y - y;
					final Bucket bu = new Bucket(this.x + x, this.y + y, width, height, bucket_side);
					if (bu.populate(container, db_map, width, height, max_width, max_height, local_list, areas)) {
						this.empty = false;
					}
					children.add(bu);
				}
			}


			/*
			int w = this.w / 2;
			int h = this.h / 2;
			for (int i=0; i<2; i++) {
				for (int j=0; j<2; j++) {
					Bucket bu = new Bucket(this.x + i * w, this.y + j * h, w, h);
					if (bu.populate(container, db_map)) {
						//Utils.log2("FILLEd " + this);
						this.empty = false;
					//} else {
					//	Utils.log2("EMPTY " + this);
					}
					children.add(bu);
				}
			}
			*/
		}
		return !this.empty;
	}

	private final boolean intersects(final Rectangle r) {
		if (r.width <= 0 || r.height <= 0 || w <= 0 || h <= 0) {
		    return false;
		}
		final int rw = r.x + r.width;
		final int rh = r.y + r.height;
		final int tw = w + x;
		final int th = h + y;
		//      overflow || intersect
		return ((rw < r.x || rw > x) &&
			(rh < r.y || rh > y) &&
			(tw < x || tw > r.x) &&
			(th < y || th > r.y));
	}

	private final boolean contains(final double px, final double py) {
		return px >= x &&
		       py >= y &&
		       px <= x + w &&
		       py <= y + h;
	}

	/** Find All Displayable objects that intersect with the given srcRect and return them ordered by stack_index. Of @param visible_only is true, then hidden Displayable objects are ignored. */
	synchronized final Collection<Displayable> find(final Rectangle srcRect, final Layer layer, final boolean visible_only) {
		final TreeMap<Integer,Displayable> accum = new TreeMap<Integer,Displayable>();
		find(accum, srcRect, layer, visible_only);
		return accum.values(); // sorted by integer key
	}

	/** Recursive search, accumulates Displayable objects that intersect the srcRect and, if @param visible_only is true, then checks first if so. */
	private void find(final TreeMap<Integer,Displayable> accum, final Rectangle srcRect, final Layer layer, final boolean visible_only) {
		if (empty || !intersects(srcRect)) return;
		if (null != children) {
			for (final Bucket bu : children) {
				bu.find(accum, srcRect, layer, visible_only);
			}
		} else {
			final Area asrc = new Area(srcRect);
			for (final Map.Entry<Integer,Displayable> entry : map.entrySet()) {
				final Displayable d = entry.getValue();
				if (visible_only && !d.isVisible()) continue;
				final Area a = d.getAreaForBucket(layer);
				if (null != a && M.intersects(asrc, a)) {
					accum.put(entry.getKey(), d);
				}
			}
		}
	}

	/** Find All Displayable objects that intersect with the given srcRect and return them ordered by stack_index. Of @param visible_only is true, then hidden Displayable objects are ignored.
	 *
	 * Fast and dirty, never returns a false negative but may return a false positive. */
	synchronized final Collection<Displayable> roughlyFind(final Rectangle srcRect, final Layer layer, final boolean visible_only) {
		final TreeMap<Integer,Displayable> accum = new TreeMap<Integer,Displayable>();
		roughlyFind(accum, srcRect, layer, visible_only);
		return accum.values(); // sorted by integer key
	}

	/** Recursive search, accumulates Displayable objects that intersect the srcRect and, if @param visible_only is true, then checks first if so. */
	private void roughlyFind(final TreeMap<Integer,Displayable> accum, final Rectangle srcRect, final Layer layer, final boolean visible_only) {
		if (empty || !intersects(srcRect)) return;
		if (null != children) {
			for (final Bucket bu : children) {
				bu.roughlyFind(accum, srcRect, layer, visible_only);
			}
		} else {
			//final Rectangle tmp = new Rectangle();
			//final Area asrc = new Area(srcRect);
			final Rectangle BOX = new Rectangle(x, y, w, h);
			for (final Map.Entry<Integer,Displayable> entry : map.entrySet()) {
				final Displayable d = entry.getValue();
				if (visible_only && !d.isVisible()) continue;
				/* // Too slow for a rough search as needed by DisplayCanvas.gatherDisplayables!
				 * // That method calls LayerSet.findZDisplayables(Layer, Rectangle, boolean) which calls here
				final Area a = d.getAreaForBucket(layer);
				if (null != a && M.intersects(asrc, a)) {
					accum.put(entry.getKey(), d);
				}
				*/
				// Instead:
				if (d.isRoughlyInside(layer, BOX)) {
					accum.put(entry.getKey(), d);
				}
			}
		}
	}

	/** Find All Displayable objects that intersect with the given srcRect and return them ordered by stack_index. Of @param visible_only is true, then hidden Displayable objects are ignored. */
	synchronized final Collection<Displayable> find(final Class<?> c, final Rectangle srcRect, final Layer layer, final boolean visible_only, final boolean instance_of) {
		final TreeMap<Integer,Displayable> accum = new TreeMap<Integer,Displayable>();
		find(accum, c, srcRect, layer, visible_only, instance_of);
		return accum.values(); // sorted by integer key
	}

	/** Recursive search, accumulates Displayable objects that intersect the srcRect and, if @param visible_only is true, then checks first if so. */
	private void find(final TreeMap<Integer,Displayable> accum, final Class<?> c, final Rectangle srcRect, final Layer layer, final boolean visible_only, final boolean instance_of) {
		if (empty || !intersects(srcRect)) return;
		if (null != children) {
			for (final Bucket bu : children) {
				bu.find(accum, c, srcRect, layer, visible_only, instance_of);
			}
		} else {
			final Area asrc = new Area(srcRect);
			if (instance_of) {
				for (final Map.Entry<Integer,Displayable> entry : map.entrySet()) {
					final Displayable d = entry.getValue();
					if (visible_only && !d.isVisible()) continue;
					if (c.isAssignableFrom(d.getClass())) {
						final Area a = d.getAreaForBucket(layer);
						if (null != a && M.intersects(asrc, a)) {
							accum.put(entry.getKey(), d);
						}
					}
				}
			} else {
				for (final Map.Entry<Integer,Displayable> entry : map.entrySet()) {
					final Displayable d = entry.getValue();
					if (visible_only && !d.isVisible()) continue;
					if (d.getClass() == c) {
						final Area a = d.getAreaForBucket(layer);
						if (null != a && M.intersects(asrc, a)) {
							accum.put(entry.getKey(), d);
						}
					}
				}
			}
		}
	}

	/** Find all Displayable objects that contain the given point at the given layer (here layer acts as the Z coordinate, then) and return them ordered by stack_index. If @param visible_only is trye, then hidden Displayable objects are ignored. */
	synchronized final Collection<Displayable> find(final double px, final double py, final Layer layer, final boolean visible_only) {
		final TreeMap<Integer,Displayable> accum = new TreeMap<Integer,Displayable>();
		find(accum, px, py, layer, visible_only);
		return accum.values(); // sorted by integer key
	}
	/** Recursive search, accumulates Displayable objects that contain the given point and, if @param visible_only is true, then checks first if so. */
	private void find(final TreeMap<Integer,Displayable> accum, final double px, final double py, final Layer layer, final boolean visible_only) {
		if (empty || !contains(px, py)) return;
		if (null != children) {
			for (final Bucket bu : children) {
				 bu.find(accum, px, py, layer, visible_only);
			}
		} else {
			for (final Map.Entry<Integer,Displayable> entry : map.entrySet()) {
				final Displayable d = entry.getValue();
				if (visible_only && !d.isVisible()) continue;
				if (d.contains(layer, px, py)) {
					accum.put(entry.getKey(), d);
				}
			}
			//Utils.log2("Bucket with " + map.size() + " contains click " + this.toString());
		}
	}

	/** Find all Displayable objects that contain the given point at the given layer (here layer acts as the Z coordinate, then) and return them ordered by stack_index. If @param visible_only is trye, then hidden Displayable objects are ignored. */
	synchronized final Collection<Displayable> find(final Class<?> c, final double px, final double py, final Layer layer, final boolean visible_only, final boolean instance_of) {
		final TreeMap<Integer,Displayable> accum = new TreeMap<Integer,Displayable>();
		find(accum, c, px, py, layer, visible_only, instance_of);
		return accum.values(); // sorted by integer key
	}
	/** Recursive search, accumulates Displayable objects that contain the given point and, if @param visible_only is true, then checks first if so. */
	private void find(final TreeMap<Integer,Displayable> accum, final Class<?> c, final double px, final double py, final Layer layer, final boolean visible_only, final boolean instance_of) {
		if (empty || !contains(px, py)) return;
		if (null != children) {
			for (final Bucket bu : children) {
				 bu.find(accum, c, px, py, layer, visible_only, instance_of);
			}
		} else {
			if (instance_of) {
				for (final Map.Entry<Integer,Displayable> entry : map.entrySet()) {
					final Displayable d = entry.getValue();
					if (visible_only && !d.isVisible()) continue;
					if (c.isAssignableFrom(d.getClass()) && d.contains(layer, px, py)) {
						accum.put(entry.getKey(), d);
					}
				}
			} else {
				for (final Map.Entry<Integer,Displayable> entry : map.entrySet()) {
					final Displayable d = entry.getValue();
					if (visible_only && !d.isVisible()) continue;
					if (d.getClass() == c && d.contains(layer, px, py)) {
						accum.put(entry.getKey(), d);
					}
				}
			}
		}
	}

	/** Find all Displayable objects that intersect the given Area and return them ordered by stack_index. If @param visible_only is trye, then hidden Displayable objects are ignored. */
	synchronized final Collection<Displayable> find(final Area area, final Layer layer, final boolean visible_only) {
		final TreeMap<Integer,Displayable> accum = new TreeMap<Integer,Displayable>();
		find(accum, area, layer, visible_only);
		return accum.values(); // sorted by integer key
	}
	/** Recursive search, accumulates Displayable objects that contain the given point and, if @param visible_only is true, then checks first if so. */
	private void find(final TreeMap<Integer,Displayable> accum, final Area area, final Layer layer, final boolean visible_only) {
		if (empty || !intersects(area.getBounds())) return;
		if (null != children) {
			for (final Bucket bu : children) {
				 bu.find(accum, area, layer, visible_only);
			}
		} else {
			for (final Map.Entry<Integer,Displayable> entry : map.entrySet()) {
				final Displayable d = entry.getValue();
				if (visible_only && !d.isVisible()) continue;
				if (d.intersects(layer, area)) {
					accum.put(entry.getKey(), d);
				}
			}
		}
	}
	/** Find all Displayable objects that intersect the given Area and return them ordered by stack_index. If @param visible_only is trye, then hidden Displayable objects are ignored. */
	synchronized final Collection<Displayable> find(final Class<?> c, final Area area, final Layer layer, final boolean visible_only, final boolean instance_of) {
		final TreeMap<Integer,Displayable> accum = new TreeMap<Integer,Displayable>();
		find(accum, c, area, layer, visible_only, instance_of);
		return accum.values(); // sorted by integer key
	}
	/** Recursive search, accumulates Displayable objects that contain the given point and, if @param visible_only is true, then checks first if so. */
	private void find(final TreeMap<Integer,Displayable> accum, final Class<?> c, final Area area, final Layer layer, final boolean visible_only, final boolean instance_of) {
		if (empty || !intersects(area.getBounds())) return;
		if (null != children) {
			for (final Bucket bu : children) {
				 bu.find(accum, c, area, layer, visible_only, instance_of);
			}
		} else {
			if (instance_of) {
				for (final Map.Entry<Integer,Displayable> entry : map.entrySet()) {
					final Displayable d = entry.getValue();
					if (visible_only && !d.isVisible()) continue;
					if (c.isAssignableFrom(d.getClass()) && d.intersects(layer, area)) {
						accum.put(entry.getKey(), d);
					}
				}
			} else {
				for (final Map.Entry<Integer,Displayable> entry : map.entrySet()) {
					final Displayable d = entry.getValue();
					if (visible_only && !d.isVisible()) continue;
					if (d.getClass() == c && d.intersects(layer, area)) {
						accum.put(entry.getKey(), d);
					}
				}
			}
		}
	}

	/** Update a Displayable's stack index from old to new, or a range. */
	synchronized final void updateRange(final Bucketable container, final Displayable d, final int old_i, final int new_i) {
		// Build a map with the new indices
		final HashMap<Displayable,Integer> stack_indices = new HashMap<Displayable,Integer>();
		final ArrayList<? extends Displayable> dlist = container.getDisplayableList();
		for (int i=old_i; i<=new_i; i++) {
			stack_indices.put(dlist.get(i), i);
		}
		updateRange(container, old_i, new_i, stack_indices);
	}
	/*
	final Set<Displayable> removeRange(final Bucketable container, final int first, final int last) {
		final HashSet<Displayable> hs = new HashSet<Displayable>();
		removeRange(container, first, last, hs);
		return hs;
	}
	*/
	/** Accumulate removed Displayable instances into the HashSet. */
	/*
	final private void removeRange(final Bucketable container, final int first, final int last, final HashSet<Displayable> hs) {
		if (null != children) {
			for (Bucket bu : children) bu.removeRange(container, first, last, hs);
		} else if (null != map) {
			// remove entire range
			for (int i=first; i<=last; i++) {
				final Displayable d =  map.remove(i);
				if (null != d) hs.add(d);
			}
		}
	}
	*/

	final private void updateRange(final Bucketable container, final int first, final int last, final HashMap<Displayable,Integer> new_stack_indices) {
		if (null != children) {
			for (final Bucket bu : children) bu.updateRange(container, first, last, new_stack_indices);
		} else if (null != map) {
			// remove range
			// (in two steps, to avoid overwriting existing entries)
			final ArrayList<Displayable> a = new ArrayList<Displayable>(last - first + 1);
			for (int i=first; i<=last; i++) {
				final Displayable d =  map.remove(i);
				if (null != d) a.add(d);
			}
			// re-add range with new stack_index keys
			for (final Displayable d : a) map.put(new_stack_indices.get(d), d);
		}
	}

	/** Remove from wherever it is, then test if it's in that bucket, otherwise re-add. */
	synchronized final void updatePosition(final Displayable d, final Layer layer, final HashMap<Displayable,HashSet<Bucket>> db_map) {
		final HashSet<Bucket> hs = db_map.get(d);
		final Area a = d.getAreaForBucket(layer);
		final int stack_index = d.getBucketable().getDisplayableList().indexOf(d);
		if (null != hs) {
			for (final Iterator<Bucket> it = hs.iterator(); it.hasNext(); ) {
				final Bucket bu = it.next();
				if (null != a && a.intersects(bu.x, bu.y, bu.w, bu.h)) continue; // bu.intersects(box)) continue; // no change of bucket: lower-right corner still within the bucket
				// else, remove
				bu.map.remove(stack_index);
				it.remove();
			}
		}
		// insert wherever appropriate, if not there
		if (null != a) this.put(stack_index, d, layer, a, db_map);
	}

	/** Add the given Displayable to all buckets that intercept its bounding box. */
	synchronized final void put(final int stack_index, final Displayable d, final Layer layer, final HashMap<Displayable,HashSet<Bucket>> db_map) {
		put(stack_index, d, layer, d.getAreaForBucket(layer), db_map);
	}
	synchronized final void put(final int stack_index, final Displayable d, final Layer layer, final Area a, final HashMap<Displayable,HashSet<Bucket>> db_map) {
		if (null == a) return;
		/*
		if (0 == box.width || 0 == box.height) {
			// d doesn't contain any data: use whole 2D world
			box.width = (int) layer.getLayerWidth();
			box.height = (int) layer.getLayerHeight();
		}
		*/
		putIn(stack_index, d, a, db_map);
	}
	private final void putIn(final int stack_index, final Displayable d, final Area a, final HashMap<Displayable,HashSet<Bucket>> db_map) {
		if (!a.intersects(x, y, w, h)) return;
		// there will be at least one now
		this.empty = false;
		if (null != children) {
			for (final Bucket bu : children) bu.putIn(stack_index, d, a, db_map);
		} else if (null != map) {
			map.put(stack_index, d);
			putToBucketMap(d, db_map); // the db_map
		}
	}

	/*
	private void debugMap(String title) {
		if (null == map) return;
		Utils.log2("@@@ " + title);
		for (final Map.Entry<Integer,Displayable> e: map.entrySet()) {
			Utils.log2("k,v : " + e.getKey() + " , " + e.getValue());
		}
	}
	*/

	final private void putToBucketMap(final Displayable d, final HashMap<Displayable,HashSet<Bucket>> db_map) {
		HashSet<Bucket> list = db_map.get(d);
		if (null == list) {
			list = new HashSet<Bucket>();
			db_map.put(d, list);
			list.add(this);
		} else list.add(this);
	}
	/*
	final private void removeFromBucketMap(final Displayable d, final HashMap<Displayable,ArrayList<Bucket>> db_map) {
		ArrayList<Bucket> list = db_map.get(d);
		if (null == list) return;
		list.remove(d);
		if (0 == list.size()) db_map.remove(d);
	}
	*/

	/** Returns whether the stack index was successfully removed.
	 *  Assumes that 'd' is in this Bucket at old_stack_index. */
	final private boolean remove2(final Displayable d, final int old_stack_index, final HashMap<Displayable,Integer> new_stack_indices) {
		boolean success = true;
		if (null != children) {
			this.empty = true;
			for (final Bucket bu : children) {
				if (!bu.remove2(d, old_stack_index, new_stack_indices)) {
					this.empty = false;
					success = false;
				}
			}
			return success;
		} else if (null != map) {
			reindex(new_stack_indices);
		}
		return success;
	}

	/** Returns whether the stack index was successfully removed .*/
	synchronized final boolean remove(final Displayable d, final int old_stack_index, final HashMap<Displayable,Integer> new_stack_indices) {
		return remove2(d, old_stack_index, new_stack_indices);
	}
	
	/** Returns whether this bucket is empty of Displayable objects. */
	final private boolean removeAll2(final Collection<Integer> old_stack_indices, final HashMap<Displayable,Integer> new_stack_indices) {
		if (null != children) {
			this.empty = true;
			for (final Bucket bu : children) {
				if (!bu.removeAll2(old_stack_indices, new_stack_indices)) this.empty = false;
			}
		} else if (null != map) {
			reindex(new_stack_indices);
			return map.isEmpty();
		}
		return true;
	}
	
	synchronized final void removeAll(final Collection<Integer> old_stack_indices, final HashMap<Displayable,Integer> new_stack_indices) {
		removeAll2(old_stack_indices, new_stack_indices);
	}
	
	final void reindex(final HashMap<Displayable,Integer> new_stack_indices) {
		if (null == new_stack_indices) return;
		if (null != children) {
			for (final Bucket bu : children) {
				bu.reindex(new_stack_indices);
			}
		} else if (null != map) {
			final HashSet<Displayable> hs = new HashSet<Displayable>(this.map.values());
			this.map.clear();
			for (final Displayable d : hs) {
				final Integer i = new_stack_indices.get(d);
				if (null == i) {
					Utils.log2("WARNING: Bucket.reindex could not find an index for " + d);
					continue;
				}
				this.map.put(i, d);
			}
		}
	}

	synchronized public void paint(Graphics2D g, Rectangle srcRect, double mag, Color color) {
		if (null == map) {
			for (final Bucket bu : children) bu.paint(g, srcRect, mag, color);
			return;
		}
		//Utils.log("going to paint ... ");
		//if (!intersects(srcRect)) return;
		//Utils.log("painting : " + x + ", " + y + ", " + w + ", " + h);
		final Graphics2D g2d = (Graphics2D)g;
		final Stroke original_stroke = g2d.getStroke();
		AffineTransform original = g2d.getTransform();
		g2d.setTransform(new AffineTransform());
		g2d.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
		g.setColor(color);
		g.drawRect((int)((x - srcRect.x) * mag), (int)((y-srcRect.y)*mag), (int)(w*mag), (int)(h*mag));
		g2d.setStroke(original_stroke);
		g2d.drawString(Integer.toString(map.size()), (int)((x - srcRect.x + w/2) * mag), (int)((y - srcRect.y + h/2) * mag));
		g2d.setTransform(original);
	}

	/** Determine whether the rectangle is smaller than the layer dimensions padded in by one bucket_side -- if not, makes little sense to use buckets, and it's better to do linear search without the TreeMap overhead. */
	public final boolean isBetter(final Rectangle r, final Bucketable container) {
		/*
		final boolean b = r.width * r.height < (layer.getLayerWidth() - bucket_side) * (layer.getLayerHeight() - bucket_side);
		Utils.log2("isBetter: " + b);
		if (b) {
			Utils.log2("\t r is " + r.width + ", " + r.height);
			Utils.log2("\t o is " + (int)(layer.getLayerWidth() - bucket_side) + ", " + (int)(layer.getLayerHeight() * bucket_side));
		}
		return b;
		*/
		return r.width * r.height < (container.getLayerWidth() - bucket_side) * (container.getLayerHeight() - bucket_side);
	}

	private final ArrayList<Bucket>getChildren(final ArrayList<Bucket> bus) {
		if (null != children) {
			for (final Bucket bu : children) {
				bu.getChildren(bus);
			}
		} else if (null != map) {
			bus.add(this);
		}
		return bus;
	}

	public void debug() {
		Utils.log2("total map buckets: " + getChildren(new ArrayList<Bucket>()).size());
	}

	static public int getBucketSide(final Bucketable container, final Layer la) {
		if (null != container.getProject().getProperty("bucket_side")) {
			final int size = (int)container.getProject().getProperty("bucket_side", Bucket.MIN_BUCKET_SIZE);
			if (size < Bucket.MIN_BUCKET_SIZE) {
				Utils.logAll("WARNING: bucket side (" + size + ") is smaller than the recommended minimum of " + Bucket.MIN_BUCKET_SIZE
						+ "\nYou may adjust the bucket side in the 'Project - Properties' popup menu.");
			}
			return size;
		} else {
			// estimate median
			final ArrayList<? extends Displayable> col = container.getDisplayableList();
			if (0 == col.size()) return Bucket.MIN_BUCKET_SIZE;
			final int[] sizes = new int[col.size()];
			int i = 0;
			for (final Displayable d : col) {
				Area a = d.getAreaForBucket(la);
				if (null == a) continue;
				Rectangle r = a.getBounds();
				sizes[i++] = Math.max(r.width, r.height);
			}
			Arrays.sort(sizes);
			int size = 2 * sizes[sizes.length/2];
			return size > Bucket.MIN_BUCKET_SIZE ? size : Bucket.MIN_BUCKET_SIZE;
		}
	}
}
