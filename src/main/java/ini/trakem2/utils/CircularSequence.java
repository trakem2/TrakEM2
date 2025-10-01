/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2025 Albert Cardona, Stephan Saalfeld and others.
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
package ini.trakem2.utils;

public final class CircularSequence
{
	int i;
	final private int size;

	/** A sequence of integers from 0 to {@code size}
	 * that cycle back to zero when reaching the end;
	 * the starting point is the last point in the sequence,
	 * so that a call to {@link #next()} delivers the first value, 0.
	 * 
	 * @param size The length of the range to cycle over.
	 */
	public CircularSequence(final int size) {
		this.size = size;
		this.i = size -1;
	}
	final public int next() {
		++i;
		i = i % size;
		return i;
	}
	final public int previous() {
		--i;
		i = i % size;
		return i;
	}
	/** Will wrap around if {@code k<0} or {@code k>size}. */
	final public int setPosition(final int k) {
		i = k;
		if (i < 0) i = size - ((-i) % size);
		else i = i % size;
		return i;
	}
	/** Will wrap around. */
	final public int move(final int inc) {
		return setPosition(i + inc);
	}
}
