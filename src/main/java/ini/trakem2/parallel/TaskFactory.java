/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2024 Albert Cardona, Stephan Saalfeld and others.
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
package ini.trakem2.parallel;

import java.util.concurrent.Callable;

public abstract class TaskFactory<I,O> {

	/** Generates a Callable task for an ExecutorService to process @param input.
	 *  Unless overriden, will simply call process(input); */
	public Callable<O> create(final I input) {
		return new Callable<O>() {
			public O call() {
				return process(input);
			}
		};
	}

	/** The actual processing on the given @param input;
	 *  override to define a task to be performed over @param input.
	 *  By default does nothing and returns null. */
	public O process(final I input) { return null; }
}
