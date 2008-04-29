/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005, 2006 Albert Cardona and Rodney Douglas.

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

import ini.trakem2.utils.Utils;

import javax.swing.*;
import java.awt.event.*;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Event;
import java.awt.Dimension;


public class DisplayablePanel extends JPanel implements MouseListener, ItemListener {

	static public final int HEIGHT = 52;

	private JCheckBox c;
	private JLabel title;
	private SnapshotPanel sp;

	private Display display;
	private Displayable d;

	public DisplayablePanel(Display display, Displayable d) {
		this.display = display;
		this.d = d;

		this.c = new JCheckBox();
		this.c.setSelected(d.isVisible());
		this.c.addItemListener(this);
		this.sp = new SnapshotPanel(display, d);
		title = new DisplayableTitleLabel(makeUpdatedTitle());
		title.addMouseListener(this);
		setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
		add(c);
		add(sp);
		add(title);

		Dimension dim = new Dimension(250, HEIGHT);
		setMinimumSize(dim);
		setMaximumSize(dim);
		setPreferredSize(dim);

		addMouseListener(this);
		setBackground(Color.white);
		setBorder(BorderFactory.createLineBorder(Color.black));
	}

	public void remake() {
		sp.remake();
	}

	/** For instance-recycling purposes. */
	public void set(final Displayable d) {
		this.d = d;
		title.setText(makeUpdatedTitle());
		sp.set(d);
	}

	public void setActive(final boolean active) {
		if (active) {
			setBackground(Color.cyan);
		} else {
			setBackground(Color.white);
		}
	}

	public void paint(final Graphics g) {
		if (display.isSelected(d)) {
			if (null != display.getActive() && display.getActive().equals(d)) { // can be null when initializing ... because swing is designed with built-in async
				setBackground(Color.cyan);
			} else {
				setBackground(Color.pink);
			}
		} else {
			setBackground(Color.white);
		}
		super.paint(g);
	}

	private String makeUpdatedTitle() {
		if (null == d) Utils.log("null d");
		else if (null == d.getTitle()) Utils.log("null title");
		final Class c = d.getClass();
		if (c.equals(Patch.class)) {
			return d.getTitle();
		} else if (c.equals(DLabel.class)) {
			return d.getTitle().replace('\n', ' ');
		} else {
			// gather name of the enclosing object in the project tree
			return d.getProject().getMeaningfulTitle(d);
		}
	}

	public void updateTitle() {
		title.setText(makeUpdatedTitle());
	}

	public void itemStateChanged(ItemEvent ie) {
		Object source = ie.getSource();
		if (source.equals(c)) {
			if (ie.getStateChange() == ItemEvent.SELECTED) {
				d.setVisible(true);
			} else if (ie.getStateChange() == ItemEvent.DESELECTED) {
				// Prevent hiding when transforming
				if (Display.isTransforming(d)) {
					Utils.showStatus("Transforming! Can't change visibility.");
					c.setSelected(true);
					return;
				}
				d.setVisible(false);
			}
			//display.getNavigator().repaint(d);
			//Display.updateVisibilityCheckbox(d.layer, d, display);
		}
	}

	public void mousePressed(MouseEvent me) {
		if (display.isTransforming()) return;
		display.select(d, me.isShiftDown());
		if (me.isPopupTrigger() || me.isControlDown() || MouseEvent.BUTTON2 == me.getButton() || 0 != (me.getModifiers() & Event.META_MASK)) {
			display.getPopupMenu().show(this, me.getX(), me.getY());
		}
	}

	public void mouseReleased(MouseEvent me) {}
	public void mouseEntered(MouseEvent me) {}
	public void mouseExited (MouseEvent me) {}
	public void mouseClicked(MouseEvent me) {}

	public String toString() {
		return "Displayable panel for " + d.toString();
	}

	protected void updateVisibilityCheckbox() {
		c.setSelected(d.isVisible());
	}
}
