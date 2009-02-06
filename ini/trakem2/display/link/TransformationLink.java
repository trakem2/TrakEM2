package ini.trakem2.display.link;
import ini.trakem2.Project;
import ini.trakem2.display.Displayable;
import ini.trakem2.persistence.DBObject;

/** Keeps the AffineTransform of two displayable objects linked: when one is modified, so is the other. */
public class TransformationLink extends Link {

	public TransformationLink(final Displayable origin, final Displayable target) {
		super(origin, target);
	}

	public TransformationLink() {}

	public boolean init(final Displayable origin, final String data) throws IllegalArgumentException {
		long target_id = -1;
		try {
			target_id = Long.parseLong(data);
		} catch (NumberFormatException nfe) {
			throw new IllegalArgumentException("Cannot reconstruct link for origin #" + origin.getId() + " with data: " + data);
		}
		this.origin = origin;
		final DBObject dbo = origin.getProject().findById(target_id);
		if (null == origin || !(dbo instanceof Displayable)) {
			throw new IllegalArgumentException("Invalid Link: can't find target!\nProblematic origin #" + origin.getId() + " data string was: " + data);
		}
		this.target = (Displayable) dbo;
		return true;
	}

	/** Returns an XML attribute content safe String representation of this link (that is, no ")
	 *  which can then be passed to the init function to recreate it. */
	public String asXML() {
		return new StringBuffer().append(origin.getId()).append(' ').append(target.getId()).toString();
	}
}
