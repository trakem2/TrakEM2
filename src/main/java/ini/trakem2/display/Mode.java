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

import ini.trakem2.display.graphics.GraphicsSource;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;

public interface Mode {

	public GraphicsSource getGraphicsSource();
	public boolean canChangeLayer();
	public boolean canZoom();
	public boolean canPan();
	public boolean isDragging();
	public void undoOneStep();
	public void redoOneStep();

	public void mousePressed(MouseEvent me, int x_p, int y_p, double magnification);
	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old);
	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r);

	public void srcRectUpdated(Rectangle srcRect, double magnification);
	public void magnificationUpdated(Rectangle srcRect, double magnification);

	public boolean apply();
	public boolean cancel();

	public Rectangle getRepaintBounds();
}
