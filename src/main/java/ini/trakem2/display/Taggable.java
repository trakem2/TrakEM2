/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2021 Albert Cardona, Stephan Saalfeld and others.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
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
