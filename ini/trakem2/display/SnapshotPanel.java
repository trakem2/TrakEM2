/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005,2006 Albert Cardona and Rodney Douglas.

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


import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Dimension;
import javax.swing.JPanel;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;


public class SnapshotPanel extends JPanel implements MouseListener {

	private Display display;
	private Displayable d;
	static public final int FIXED_HEIGHT = 50;

	public SnapshotPanel(Display display, Displayable d) {
		this.display = display;
		this.d = d;
		remake();
	}

	/** Redefine dimensions. */
	public void remake() {
		int width = (int)(FIXED_HEIGHT / d.getLayer().getLayerHeight() * d.getLayer().getLayerWidth());
		Dimension dim = new Dimension(width, FIXED_HEIGHT);
		setMinimumSize(dim);
		setMaximumSize(dim);
		setPreferredSize(dim);
	}

	public void update(Graphics g) {
		paint(g);
	}

	/** Paint the snapshot image over a black background that represents a scaled Layer. */
	public void paint(Graphics g) {
		if (null == g) return; // happens if not visible
		if (!display.isPartiallyWithinViewport(d)) return;
		Snapshot snapshot = d.getSnapshot();
		g.setColor(Color.black);
		g.fillRect(0, 0, this.getWidth(), this.getHeight());
		double scale = FIXED_HEIGHT / d.getLayer().getLayerHeight();
		Graphics2D g2d = (Graphics2D)g;
		g2d.scale(scale, scale);
		snapshot.paintTo(g2d, display.getLayer(), scale);
	}

	public void mousePressed(MouseEvent me) {
		//must enable cancel!//if (display.isTransforming()) return;
		display.setActive(d);
		if (me.isPopupTrigger() || me.isControlDown() || MouseEvent.BUTTON2 == me.getButton()) {
			Display.showPopup(this, me.getX(), me.getY());
		}
	}
	public void mouseReleased(MouseEvent me) {}
	public void mouseEntered(MouseEvent me) {}
	public void mouseExited (MouseEvent me) {}
	public void mouseClicked(MouseEvent me) {}
}

