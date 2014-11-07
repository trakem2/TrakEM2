package ini.trakem2.display;

import java.awt.BasicStroke;
import java.awt.Color;
import java.util.Set;

public interface Taggable {

	static public final Color TAG_BACKGROUND = new Color(255, 252, 139); // pale yellow
	static public final BasicStroke DASHED_STROKE = new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 3, new float[]{ 4, 2, 4, 2 }, 0);

	/** @return true if the tag wasn't there already. */
	public boolean addTag(Tag tag);

	/** @return true if the tag was there. */
	public boolean removeTag(Tag tag);

	/** @return the tags, if any, or null. */
	public Set<Tag> getTags();

	/** @return the tags, if any, or null. */
	public Set<Tag> removeAllTags();
}
