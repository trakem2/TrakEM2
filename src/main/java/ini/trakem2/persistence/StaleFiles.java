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
package ini.trakem2.persistence;

import ini.trakem2.Project;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Methods to remove files that are no longer referenced from the current {@link Patch} instances of a {@link Project}. */
public class StaleFiles {

	static public interface Path {
		public String get(Patch patch);
		public String extension();
	}
	
	static public final class CoordinateTransformPath implements Path {
		@Override
		public final String get(final Patch patch) {
			return patch.getCoordinateTransformFilePath();
		}

		@Override
		public final String extension() {
			return ".ct";
		}
	}
	
	static public final class AlphaMaskPath implements Path {
		@Override
		public final String get(final Patch patch) {
			return patch.getAlphaMaskFilePath();
		}

		@Override
		public final String extension() {
			return ".zip";
		}
	}
	
	/**
	 * Delete all files storing a coordinate transform that are now stale.
	 * Stale files are files that are no longer referenced by the {@link Patch#getCoordinateTransformId()}.
	 * 
	 * This method uses the {@link Utils#removeFile(File)}, which limits itself to files
	 * under the trakem2.* temp data directories for safety. */
	static public final boolean deleteCoordinateTransforms(final Project project) {
		return delete(project, project.getLoader().getCoordinateTransformsFolder(), new CoordinateTransformPath());
	}

	/**
	 * Delete all files storing a zipped image representing the alpha mask and which are now stale.
	 * Stale files are files that are no longer referenced by the {@link Patch#getAlphaMaskId()}.
	 * 
	 * This method uses the {@link Utils#removeFile(File)}, which limits itself to files
	 * under the trakem2.* temp data directories for safety. */
	static public final boolean deleteAlphaMasks(final Project project) {
		return delete(project, project.getLoader().getMasksFolder(), new AlphaMaskPath());
	}

	/** Generic method called by other methods of this class. */
	static public final boolean delete(final Project project, final String topDir, final Path g) {
		if (null == topDir) return true;
		// Collect the set of files to keep
		final HashSet<String> keepers = new HashSet<String>();
		for (final Layer l : project.getRootLayerSet().getLayers()) {
			for (final Patch p : l.getAll(Patch.class)) {
				String path = g.get(p);
				if (null != path) keepers.add(path);
			}
		}
		// Iterate all directories, recursively
		final String extension = g.extension();
		final LinkedList<File> subdirs = new LinkedList<File>();
		subdirs.add(new File(topDir));
		final AtomicInteger counter = new AtomicInteger(0);
		final ExecutorService exec = Utils.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()), "Stale-file-remover");
		while (!subdirs.isEmpty()) {
			final File fdir = subdirs.removeFirst();
			final String absPath = fdir.getAbsolutePath();
			for (final String s : fdir.list()) {
				final String path = absPath + "/" + s;
				if (s.endsWith(extension)) {
					if (keepers.contains(path)) continue;
					// Else, delete the file, which is by definition stale
					exec.submit(new Runnable() {
						public void run() {
							if (!new File(path).delete()) {
								Utils.log2("Failed to delete: " + path);
								counter.incrementAndGet();
							}
						}
					});
				} else {
					final File f = new File(path);
					if (f.isDirectory()) {
						subdirs.add(f);
					}
				}
			}
		}
		// Do not accept more tasks, but execute all submitted tasks
		exec.shutdown();
		// Wait maximum for an unreasonable amount of time
		try {
			exec.awaitTermination(1, TimeUnit.DAYS);
		} catch (InterruptedException e) {
			IJError.print(e);
		}
		if (counter.get() > 0) {
			Utils.log("ERROR: failed to delete " + counter.get() + " files.\n        See the stdout log for details.");
		}
		return 0 == counter.get();
	}
}
