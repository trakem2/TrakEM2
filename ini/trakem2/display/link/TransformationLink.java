package ini.trakem2.display.link;
import ini.trakem2.Project;
import ini.trakem2.display.Displayable;

/** Keeps the AffineTransform of two displayable objects linked: when one is modified, so is the other. */
public class TransformationLink extends Link {

	public TransformationLink(final Displayable origin, final Displayable target) {
		super(origin, target);
	}

	public TransformationLink() {}

	public boolean init(final Project project, final String data) throws IllegalArgumentException {
		final int ispace = data.indexOf(' ');
		if (-1 == ispace || 0 == ispace || data.length() -1 == ispace) {
			throw new IllegalArgumentException("Cannot reconstruct link from: " + data);
		}
		this.origin = (Displayable) project.getRootLayerSet().findById(Long.parseLong(data.substring(0, ispace)));
		this.target = (Displayable) project.getRootLayerSet().findById(Long.parseLong(data.substring(ispace+1)));
		if (null == origin || null == target) {
			throw new IllegalArgumentException("Invalid Link: can't point to a null Displayable!\nProblematic data string was: " + data);
		}
		return true;
	}

	/** Returns an XML attribute content safe String representation of this link (that is, no ")
	 *  which can then be passed to the init function to recreate it. */
	public String asXML() {
		return new StringBuffer().append(origin.getId()).append(' ').append(target.getId()).toString();
	}
}
