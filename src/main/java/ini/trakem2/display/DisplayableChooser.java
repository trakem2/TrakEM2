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


package ini.trakem2.display;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Collection;

/** A class to be used when a click to select Displayable objects on the Display detects that there are more than one under the mouse cursor.
 *
 *
 */
public final class DisplayableChooser implements ActionListener {

	private Collection<Displayable> al_under;
	private boolean waiting = true;
	private Displayable chosen = null;
	private Object lock;

	public DisplayableChooser(Collection<Displayable> al_under, Object lock) {
		this.al_under = al_under;
		this.lock = lock;
	}

	public void actionPerformed(final ActionEvent ae) {
		String command = ae.getActionCommand();
		//find the object that has the command as title
		for (final Displayable d : al_under) {
			if (d.toString().equals(command)) {
				chosen = d;
				break;
			}
		}
		//no more waiting!
		synchronized(this.lock) {
			this.lock.notifyAll();
			waiting = false;
		}
	}

	public boolean isWaiting() {
		return this.waiting;
	}

	public Displayable getChosen() {
		return this.chosen;
	}
}
