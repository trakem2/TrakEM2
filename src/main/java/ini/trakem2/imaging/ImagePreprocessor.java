/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2022 Albert Cardona, Stephan Saalfeld and others.
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


package ini.trakem2.imaging;
import ij.ImagePlus;

/** Any class implementing this interface is suitable as an image preprocessor for a TrakEM2 project.
 * <p>
 *  The role of the preprocessor is to do whatever is necessary to the given ImagePlus object before TrakEM2 ever sees the pixels of its ImageProcessor.
 * </p>
 */
public interface ImagePreprocessor {
	public void run(ImagePlus imp, String arg);
}
