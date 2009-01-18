package ini.trakem2.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;

/** A class to represent a generic undo/redo history.
 *  Keeps a list of objects and the current index.
 *  When adding, and the index not being at the last slot, the list is cleared from that point onward.
 *
 *  All added objects must implement the History.Step interface.
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
		if (-1 == index) {
			if (list.size() > 0) list.clear();
		} else {
			// Crop list: from start to index, inclusive
			list = list.subList(0, index+1);
		}
		// TODO above some steps may not be returned!

		++index;
		list.add(step);

		Utils.log2("Added step: index=" + index + " list.size=" + list.size());

		if (-1 != max_size) return resize(max_size);
		return new ArrayList<Step>();
	}

	/** Appends a step at the end of the list, without modifying the current index.
	 *  If max_size is set, resizes the list if larger than max_size. */
	synchronized public List<Step> append(final Step step) {
		list.add(step);
		if (-1 != max_size) return resize(max_size);
		return new ArrayList<Step>();
	}

	synchronized public Step getCurrent() {
		if (-1 == index) return null;
		return list.get(index);
	}

	/** Returns null if there aren't any more steps to undo. */
	synchronized public Step undoOneStep() {
		if (index < 0) return null;
		// Return the current Step at index, then decrease index.
		return list.get(index--);
	}

	/** Returns null if there aren't any more steps to redo. */
	synchronized public Step redoOneStep() {
		if (list.size() == (index +1)) return null;
		return list.get(++index);
	}

	/** Empty all elements from each Step in the list that match the given id, and return them. */
	synchronized public List remove(final long id) {
		final List al = new ArrayList();
		for (final Step step : list) {
			List rm = step.remove(id);
			if (null != rm) al.addAll(rm);
		}
		return al;
	}

	/** Remove last step from the list, and return it, if any. */
	synchronized public Step removeLast() {
		if (0 == list.size()) return null;
		if (list.size() == (index + 1)) --index;
		return list.remove(list.size()-1);
	}

	/** Resize to maximum the given size, removing from the beginning. Returns all removed elements, or an empty list if none. */
	synchronized public List<Step> resize(final int size) {
		final List<Step> al = new ArrayList<Step>();
		if (list.size() < size) return al;
		// else:
		// fix index
		final int cut = list.size() - size;
		if (index < cut) index = 0;
		else index -= cut;
		// cut list
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

	/** Returns a list with all undo steps. */
	synchronized public List<Step> getAll() {
		return new ArrayList<Step>(list);
	}

	synchronized public Step get(final int i) {
		if (i < 0 || i >= list.size()) return null;
		return list.get(i);
	}

	/** Cut the list after the index, leaving from 0 to index, inclusive, inside.
	 *  Returns removed steps. */
	synchronized public List<Step> clip() {
		final ArrayList<Step> al = new ArrayList<Step>();
		if (indexAtEnd()) return al;
		al.addAll(list.subList(index+1, list.size()));
		list = list.subList(0, index+1);
		return al;
	}

	synchronized public int size() {
		return list.size();
	}

	synchronized public int index() {
		return index;
	}

	synchronized public boolean indexAtStart() {
		return 0 == index;
	}

	synchronized public boolean indexAtEnd() {
		return (index + 1) == list.size();
	}

	synchronized public boolean canUndo() {
		return index > -1;
	}

	synchronized public boolean canRedo() {
		return index < (list.size() -1);
	}

	public interface Step<T> {
		/** Remove objects in this step that have the given id,
		 *  and return a list of them. */
		public List<T> remove(final long id);
		public boolean isEmpty();
	}
}
