/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005-2009 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 

You may contact Albert Cardona at acardona at ini.phys.ethz.ch
Institute of Neuroinformatics, University of Zurich / ETH, Switzerland.
**/

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
