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

package ini.trakem2.display;

import java.util.TreeMap;
import java.util.Collection;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Stroke;
import java.awt.BasicStroke;
import java.awt.geom.AffineTransform;

import ini.trakem2.utils.Utils;


/** Each Bucket contains up to N_CHILD_BUCKETS, or a list of Displayables contained within.
 *
 * A Bucket is a subarea of the Layer area, which contains either other Buckets or a map of stack_index vs. Displayable instances.
 *
 */
public class Bucket {

	private static final int N_CHILD_BUCKETS_SQRT = 2;
	private static final int MAX_BUCKET_SIDE = 2048;

	/** The sorted map of stack_index and Displayable objects that are fully contained in this bucket or intersect at top and left, but not at right and bottom. That is, the lower-right corner of the Displayable is contained with the area of this bucket. */
	private TreeMap<Integer,Displayable> map = null;
	/** The set of sub-buckets contained here. */
	private ArrayList<Bucket> children = null;

	private final int x,y,w,h;

	public Bucket(final int x, final int y, final int w, final int h) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		Utils.log2("new Bucket: " + x + ",  " + y + ", " + w + ", " + h);
	}

	public String toString() {
		return "Bucket: " + x + ",  " + y + ", " + w + ", " + h;
	}

	/** Multimethod: for other Bucket, test deep equality;
	 *               for an actual Rectangle, test intersection;
	 *               for a Point, test whether its contained in the Bucket; */
	/*
	public final boolean equals(final Object ob) {
		final Class c = ob.getClass();
		if (Bucket.class == c) return super.equals(ob);
		if (Point.class == c) return super.contains((Point)ob);
		// else do a "contains" operation:
		return ((Rectangle)ob).intersects(this);
	}
	*/

	/** Recursive initialization of buckets. This method is meant to be used as init, when root is null or is made new from scratch. */
	synchronized final void populate(final Bucketable container, final HashMap<Displayable,ArrayList<Bucket>> db_map) {
		if (this.w <= MAX_BUCKET_SIDE || this.h <= MAX_BUCKET_SIDE) {
			// add displayables, sorted by index
			map = new TreeMap<Integer,Displayable>();
			int i = 0;
			for (Displayable d : container.getDisplayableList()) {
				final Rectangle b = d.getBoundingBox();
				if (this.intersects(b)) {
					map.put(i, d);
					putToBucketMap(d, db_map);
				}
				i++;
			}
		} else {
			// create child buckets as subdivisions of this one
			children = new ArrayList<Bucket>(N_CHILD_BUCKETS_SQRT*N_CHILD_BUCKETS_SQRT);
			int w = this.w / N_CHILD_BUCKETS_SQRT;
			int h = this.h / N_CHILD_BUCKETS_SQRT;
			for (int i=0; i<N_CHILD_BUCKETS_SQRT; i++) {
				for (int j=0; j<N_CHILD_BUCKETS_SQRT; j++){
					Bucket bu = new Bucket(this.x + i * w, this.y + j * h, w, h);
					bu.populate(container, db_map);
					children.add(bu);
				}
			}
		}
	}

	synchronized private final boolean intersects(final Rectangle r) {
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

	synchronized private final boolean contains(final int px, final int py) {
		return px >= x &&
		       py >= y &&
		       px <= x + w &&
		       py <= y + h;
	}

	/** Find All Displayable objects that intersect with the given srcRect and return them ordered by stack_index. Of @param visible_only is true, then hidden Displayable objects are ignored. */
	synchronized final Collection<Displayable> find(final Rectangle srcRect, final boolean visible_only) {
		final TreeMap<Integer,Displayable> accum = new TreeMap<Integer,Displayable>();
		find(accum, srcRect, visible_only);
		return accum.values(); // sorted by integer key
	}

	/** Recursive search, accumulates Displayable objects that intersect the srcRect and, if @param visible_only is true, then checks first if so. */
	synchronized private void find(final TreeMap<Integer,Displayable> accum, final Rectangle srcRect, final boolean visible_only) {
		if (!intersects(srcRect)) return;
		if (null != children) {
			for (Bucket bu : children) {
				bu.find(accum, srcRect, visible_only);
			}
		} else {
			final Rectangle tmp = new Rectangle();
			for (Map.Entry<Integer,Displayable> entry : map.entrySet()) {
				final Displayable d = entry.getValue();
				if (visible_only && !d.isVisible()) continue;
				if (d.getBounds(tmp).intersects(srcRect)) {
					accum.put(entry.getKey(), d);
				}
			}
		}
	}

	/** Find all Displayable objects that contain the given point and return them ordered by stack_index. If @param visible_only is trye, then hidden Displayable objects are ignored. */
	synchronized final Collection<Displayable> find(final int px, final int py, final boolean visible_only) {
		final TreeMap<Integer,Displayable> accum = new TreeMap<Integer,Displayable>();
		find(accum, px, py, visible_only);
		return accum.values(); // sorted by integer key
	}
	/** Recursive search, accumulates Displayable objects that contain the given point and, if @param visible_only is true, then checks first if so. */
	private void find(final TreeMap<Integer,Displayable> accum, final int px, final int py, final boolean visible_only) {
		if (!contains(px, py)) return;
		if (null != children) {
			for (Bucket bu : children) {
				 bu.find(accum, px, py, visible_only);
			}
		} else {
			final Rectangle tmp = new Rectangle();
			for (Map.Entry<Integer,Displayable> entry : map.entrySet()) {
				final Displayable d = entry.getValue();
				if (visible_only && !d.isVisible()) continue;
				if (d.contains(px, py)) {
					accum.put(entry.getKey(), d);
				}
			}
		}
	}

	/** Find all Displayable objects that contain the given point and return them ordered by stack_index. If @param visible_only is trye, then hidden Displayable objects are ignored. */
	synchronized final Collection<Displayable> find(final Class c, final int px, final int py, final boolean visible_only) {
		final TreeMap<Integer,Displayable> accum = new TreeMap<Integer,Displayable>();
		find(accum, c, px, py, visible_only);
		return accum.values(); // sorted by integer key
	}
	/** Recursive search, accumulates Displayable objects that contain the given point and, if @param visible_only is true, then checks first if so. */
	private void find(final TreeMap<Integer,Displayable> accum, final Class c, final int px, final int py, final boolean visible_only) {
		if (!contains(px, py)) return;
		if (null != children) {
			for (Bucket bu : children) {
				 bu.find(accum, c, px, py, visible_only);
			}
		} else {
			final Rectangle tmp = new Rectangle();
			for (Map.Entry<Integer,Displayable> entry : map.entrySet()) {
				final Displayable d = entry.getValue();
				if (visible_only && !d.isVisible()) continue;
				if (d.getClass() == c && d.contains(px, py)) {
					accum.put(entry.getKey(), d);
				}
			}
		}
	}

	/** Find all Displayable objects that intersect the given Area and return them ordered by stack_index. If @param visible_only is trye, then hidden Displayable objects are ignored. */
	synchronized final Collection<Displayable> find(final Area area, final boolean visible_only) {
		final TreeMap<Integer,Displayable> accum = new TreeMap<Integer,Displayable>();
		find(accum, area, visible_only);
		return accum.values(); // sorted by integer key
	}
	/** Recursive search, accumulates Displayable objects that contain the given point and, if @param visible_only is true, then checks first if so. */
	private void find(final TreeMap<Integer,Displayable> accum, final Area area, final boolean visible_only) {
		if (!intersects(area.getBounds())) return;
		if (null != children) {
			for (Bucket bu : children) {
				 bu.find(accum, area, visible_only);
			}
		} else {
			final Rectangle tmp = new Rectangle();
			for (Map.Entry<Integer,Displayable> entry : map.entrySet()) {
				final Displayable d = entry.getValue();
				if (visible_only && !d.isVisible()) continue;
				if (d.intersects(area)) {
					accum.put(entry.getKey(), d);
				}
			}
		}
	}

	/** Update a Displayable's stack index from old to new, or a range. */
	synchronized final void update(final Bucketable container, final int old_i, final int new_i) {
		if (1 == Math.abs(old_i - new_i)) {
			// find entries with such numbers, and swap them
			final TreeMap<Integer,Displayable> m1 = search(old_i);
			final TreeMap<Integer,Displayable> m2 = search(new_i);
			m2.put(new_i, m1.remove(old_i));
			return;
		}
		// else, remove the entire range of entries, and trigger the update
		updateRange(container, old_i, new_i);
	}

	final private TreeMap<Integer,Displayable> search(final int i) {
		if (null != children) {
			for (Bucket bu : children) {
				final TreeMap<Integer,Displayable> m = bu.search(i);
				if (null != m) return m;
			}
		} else if (null != map) {
			final Displayable d = map.get(i);
			if (null != d) return map;
		}
		return null;
	}

	final private void updateRange(final Bucketable container, final int first, final int last) {
		if (null != children) {
			for (Bucket bu : children) bu.updateRange(container, first, last);
		} else if (null != map) {
			// brute force: remove entire range, and put again
			final ArrayList<Displayable> a = new ArrayList<Displayable>();
			for (int i=first; i<=last; i++) {
				Displayable d = map.remove(i);
				if (d != null) a.add(d);
			}
			for (Displayable d : a) {
				map.put(container.getDisplayableList().indexOf(d), d);
			}
		}
	}

	/** Remove from wherever it is, then test if it's in that bucket, otherwise re-add. */
	synchronized final void updatePosition(final Displayable d, final HashMap<Displayable,ArrayList<Bucket>> db_map) {

		final ArrayList<Bucket> list = db_map.get(d);
		if (null == list) return;
		final Rectangle box = d.getBoundingBox(null);
		final int stack_index = d.getBucketable().getDisplayableList().indexOf(d);
		for (Iterator<Bucket> it = list.iterator(); it.hasNext(); ) {
			Bucket bu = it.next();
			if (bu.intersects(box)) continue; // no change of bucket: lower-right corner still within the bucket
			// else, remove
			bu.map.remove(stack_index);
			it.remove();
		}
		// insert wherever appropriate, if not there
		this.put(stack_index, d, box);
	}

	/** Add the given Displayable to all buckets that intercept its bounding box. */
	synchronized final void put(final int stack_index, final Displayable d, final Rectangle box) {
		if (!intersects(box)) return;
		if (null != children) {
			for (Bucket bu : children) bu.put(stack_index, d, box);
		} else if (null != map) {
			map.put(stack_index, d);
			putToBucketMap(d, d.getBucketable().getBucketMap()); // the db_map
		}
	}
	private void debugMap(String title) {
		if (null == map) return;
		Utils.log2("@@@ " + title);
		for (Map.Entry<Integer,Displayable> e: map.entrySet()) {
			Utils.log2("k,v : " + e.getKey() + " , " + e.getValue());
		}
	}

	final private void putToBucketMap(final Displayable d, final HashMap<Displayable,ArrayList<Bucket>> db_map) {
		ArrayList<Bucket> list = db_map.get(d);
		if (null == list) {
			list = new ArrayList<Bucket>();
			db_map.put(d, list);
			list.add(this);
		} else if (!list.contains(this)) list.add(this);
	}
	/*
	final private void removeFromBucketMap(final Displayable d, final HashMap<Displayable,ArrayList<Bucket>> db_map) {
		ArrayList<Bucket> list = db_map.get(d);
		if (null == list) return;
		list.remove(d);
		if (0 == list.size()) db_map.remove(d);
	}
	*/

	final void remove(final int stack_index) {
		if (null != map) map.remove(stack_index);
		else if (null != children) {
			for (Bucket bu : children) bu.remove(stack_index);
		}
	}

	static final void remove(final Displayable d, final HashMap<Displayable, ArrayList<Bucket>> db_map) {
		final int stack_index = d.getBucketable().getDisplayableList().indexOf(d);
		final ArrayList<Bucket> list = db_map.get(d);
		if (null == list) return;
		for (Bucket bu : list) {
			bu.remove(stack_index);
		}
		db_map.remove(d);
	}

	public void paint(Graphics2D g, Rectangle srcRect, double mag) {
		if (null == map) {
			for (Bucket bu : children) bu.paint(g, srcRect, mag);
			return;
		}
			final Graphics2D g2d = (Graphics2D)g;
			final Stroke original_stroke = g2d.getStroke();
			AffineTransform original = g2d.getTransform();
			g2d.setTransform(new AffineTransform());
			g2d.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
		g.setColor(Color.red);
		g.drawRect((int)((x - srcRect.x) * mag), (int)((y-srcRect.y)*mag), (int)(w*mag), (int)(h*mag));
			g2d.setStroke(original_stroke);
			g2d.setTransform(original);
	}
}
