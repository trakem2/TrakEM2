package ini.trakem2.display.link;
import ini.trakem2.display.Displayable;
import ini.trakem2.Project;

/** A transformation link across layers, representing the concatenation of two slices (Patches) of a stack. */
public class SliceLink extends TransformationLink {

	public SliceLink(final Displayable origin, final Displayable target) {
		super(origin, target);
	}

	public SliceLink() {}

	public boolean init(final Project project, final String data) throws IllegalArgumentException {
		return super.init(project, data);
	}
}
