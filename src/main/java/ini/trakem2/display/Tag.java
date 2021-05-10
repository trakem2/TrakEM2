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

public class Tag implements Comparable<Tag> {
	final private Object tag;
	final private int keyCode;
	public Tag(final Object ob, final int keyCode) {
		this.tag = ob;
		this.keyCode = keyCode;
	}
	public String toString() {
		return tag.toString();
	}
	public int getKeyCode() {
		return keyCode;
	}
	public boolean equals(final Object ob) {
		final Tag t = (Tag)ob;
		return t.keyCode == this.keyCode && t.tag.equals(this.tag);
	}
	public int compareTo(final Tag t) {
		return t.tag.toString().compareTo(this.tag.toString());
	}
}
