package ini.trakem2.display;

import ini.trakem2.utils.History;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class TransformationStep implements History.Step<Displayable> {
	final HashMap<Displayable,AffineTransform> ht;
	TransformationStep(final HashMap<Displayable,AffineTransform> ht) {
		this.ht = ht;
	}
	public List<Displayable> remove(final long id) {
		final List<Displayable> al = new ArrayList<Displayable>();
		for (Iterator<Displayable> it = ht.keySet().iterator(); it.hasNext(); ) {
			final Displayable d = it.next();
			if (d.getId() == id) {
				it.remove();
			}
			al.add(d);
		}
		return al;
	}
	public boolean isEmpty() {
		return ht.isEmpty();
	}
	public boolean isIdentical(final History.Step<?> step) {
		if (step.getClass() != TransformationStep.class) return false;
		final HashMap<Displayable,AffineTransform> m = ((TransformationStep)step).ht;
		// cheap test:
		if (m.size() != this.ht.size()) return false;
		// Check each:
		for (final Map.Entry<Displayable,AffineTransform> e : m.entrySet()) {
			final AffineTransform aff = this.ht.get(e.getKey());
			if (null == aff) return false; // at least one Displayable is missing
			if (!aff.equals(e.getValue())) return false; // at least one Displayable has a different AffineTransform
		}
		return true;
	}
}
