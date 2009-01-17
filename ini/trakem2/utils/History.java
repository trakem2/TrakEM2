package ini.trakem2.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;

/** A class to represent a generic undo/redo history.
 *  Keeps a list of objects and the current index.
 *  When adding, and the index not being at the last slot, the list is cleared from that point onward.
 */
public class History {

	int index = -1;
	int max_size = -1;
	List<Step> list = new ArrayList<Step>();

	/** New unlimited history list. */
	public History() {}

	public History(final int max_size) {
		this.max_size = max_size;
	}

	/** Append a new step. If max_size is set, resizes the list if larger than max_size,
	 *  and returns all removed elements. Otherwise returns an empty list. */
	synchronized public List<Step> add(final Step step) {
		++index;
		if (list.size() == index) {
			list.add(step);
		} else {
			list = list.subList(0, index);
			list.add( step );
		}
		if (-1 != max_size) return resize(max_size);
		return new ArrayList<Step>();
	}

	/** Returns null if there aren't any more steps to undo. */
	synchronized public Step undoOneStep() {
		if (index < 1) return null;
		return list.get(--index);
	}

	/** Returns null if there aren't any more steps to redo. */
	synchronized public Step redoOneStep() {
		if (list.size() == (index +1)) return null;
		return list.get(++index);
	}

	/** Remove all steps from the list that contain the given id, and return them. */
	synchronized public List<Step> remove(final long id) {
		final List<Step> al = new ArrayList<Step>();
		for (Iterator<Step> it = list.iterator(); it.hasNext(); ) {
			Step step = it.next();
			if (id == step.getId()) {
				index--;
				it.remove();
				al.add(step);
			}
		}
		return al;
	}

	/** Resize to maximum the given size, removing from the beginning. Returns all removed elements, or an empty list if none. */
	synchronized public List<Step> resize(final int size) {
		final List<Step> al = new ArrayList<Step>();
		if (list.size() < size) return al;
		// else:
		final int cut = list.size() - size;
		al.addAll(list.subList(0, cut));
		list = list.subList(cut, list.size());
		return al;
	}

	/** Remove all steps from the list and return them. */
	synchronized public List<Step> clear() {
		final ArrayList<Step> al = new ArrayList<Step>();
		al.addAll(list);
		list.clear();
		return al;
	}

	synchronized public int size() {
		return list.size();
	}

	public interface Step {
		public long getId();
	}
}
