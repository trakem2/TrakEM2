package ini.trakem2.display.link;
import ini.trakem2.Project;
import ini.trakem2.display.Displayable;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.utils.Utils;

/** Keeps the AffineTransform of two displayable objects linked: when one is modified, so is the other. */
public class TransformationLink extends Link {

	public TransformationLink(final Displayable origin, final Displayable target) {
		super(origin, target);
	}

	public TransformationLink() {}

	public boolean init(final Displayable origin, final String data) throws IllegalArgumentException {
		if (null == origin) {
			throw new IllegalArgumentException("Cannot create a link for a null origin!");
		}
		long target_id = -1;
		try {
			target_id = Long.parseLong(data);
		} catch (NumberFormatException nfe) {
			throw new IllegalArgumentException("Cannot reconstruct link for origin #" + origin.getId() + " with data: " + data + " -- data is not a number.");
		}
		this.origin = origin;
		Utils.log2("origin layer: " + origin.getLayer());
		Utils.log2("origin layerset: " + origin.getLayerSet());
		final DBObject dbo = origin.getLayerSet().findById(target_id); // calls to getProject().findById() or getProject().getRootLayerSet().findById would fail, because the project's layer_set is not setup yet.
		if (null == origin || !(dbo instanceof Displayable)) {
			throw new IllegalArgumentException("Invalid Link: can't find target!\nProblematic origin #" + origin.getId() + " data string was: " + data);
		}
		this.target = (Displayable) dbo;
		return true;
	}
}
