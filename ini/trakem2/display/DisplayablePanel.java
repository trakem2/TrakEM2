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

import ini.trakem2.utils.Utils;

import javax.swing.*;
import java.awt.event.*;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Event;
import java.awt.Dimension;


public final class DisplayablePanel extends JPanel implements MouseListener, ItemListener {

	static public final int HEIGHT = 52;

	static private ImageIcon LOCKED = new ImageIcon(DisplayablePanel.class.getResource("/img/locked.png"));
	static private ImageIcon UNLOCKED = new ImageIcon(DisplayablePanel.class.getResource("/img/unlocked.png"));
	static private ImageIcon VISIBLE = new ImageIcon(DisplayablePanel.class.getResource("/img/visible.png"));
	static private ImageIcon INVISIBLE = new ImageIcon(DisplayablePanel.class.getResource("/img/invisible.png"));

	private JCheckBox c, c_locked;
	private JLabel title, title2;
	private JPanel titles;
	private SnapshotPanel sp;

	private Display display;
	private Displayable d;

	public DisplayablePanel(Display display, Displayable d) {
		this.display = display;
		this.d = d;

		this.c = new JCheckBox();
		this.c.setSelected(d.isVisible());
		this.c.addItemListener(this);
		this.c.setIcon(INVISIBLE);
		this.c.setSelectedIcon(VISIBLE);
		this.c.setBackground(Color.white);

		this.c_locked = new JCheckBox();
		this.c_locked.setIcon(UNLOCKED);
		this.c_locked.setSelectedIcon(LOCKED);
		this.c_locked.setSelected(d.isLocked2());
		this.c_locked.addItemListener(this);
		this.c_locked.setBackground(Color.white);

		this.sp = new SnapshotPanel(display, d);
		title = new JLabel();
		title.addMouseListener(this);
		title2 = new JLabel();
		title2.addMouseListener(this);
		titles = new JPanel();
		updateTitle();
		BoxLayout bt = new BoxLayout(titles, BoxLayout.Y_AXIS);
		titles.setLayout(bt);
		titles.setBackground(Color.white);
		titles.add(title);
		titles.add(title2);
		setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
		JPanel checkboxes = new JPanel();
		checkboxes.setBackground(Color.white);
		BoxLayout b = new BoxLayout(checkboxes, BoxLayout.Y_AXIS);
		checkboxes.setLayout(b);
		checkboxes.add(c);
		checkboxes.add(c_locked);
		add(checkboxes);
		add(sp);
		add(titles);

		Dimension dim = new Dimension(250 - Display.scrollbar_width, HEIGHT);
		setMinimumSize(dim);
		setMaximumSize(dim);
		//setPreferredSize(dim);

		addMouseListener(this);
		setBackground(Color.white);
		setBorder(BorderFactory.createLineBorder(Color.black));
	}

	/** For instance-recycling purposes. */
	public void set(final Displayable d) {
		this.d = d;
		c.setSelected(d.isVisible());
		c_locked.setSelected(d.isLocked2());
		updateTitle();
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
			if (null != display.getActive() && display.getActive() == d) { // can be null when initializing ... because swing is designed with built-in async
				setBackground(Color.cyan);
			} else {
				setBackground(Color.pink);
			}
		} else {
			setBackground(Color.white);
		}
		super.paint(g);
	}

	public void setBackground(Color c) {
		super.setBackground(c);
		if (null != titles) {
			titles.setBackground(c);
			title.setBackground(c);
			title2.setBackground(c);
		}
	}

	private String makeUpdatedTitle() {
		if (null == d) { Utils.log2("null d "); return ""; }
		else if (null == d.getTitle()) { Utils.log2("null title for " + d); return ""; }
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

	static private int MAX_CHARS = 23;

	public void updateTitle() {
		String t = makeUpdatedTitle();
		if (t.length() <= MAX_CHARS) {
			title.setText(t);
			title2.setText("");
			return;
		}
		// else split at MAX_CHARS
		// First try to see if it can be cut nicely
		int i = -1;
		int back = t.length() < ((MAX_CHARS * 3) / 2) ? 12 : 5;
		for (int k=MAX_CHARS-1; k>MAX_CHARS-6; k--) {
			char c = t.charAt(k);
			switch (c) {
				case ' ':
				case '/':
				case '_':
				case '.':
					i = k; break;
				default:
					break;
			}
		}
		if (-1 == i) i = MAX_CHARS; // cut at MAX_CHARS anyway
		title.setText(t.substring(0, i));
		String t2 = t.substring(i);
		if (t2.length() > MAX_CHARS) {
			t2 = new StringBuilder(t2.substring(0, 7)).append("...").append(t2.substring(t2.length()-13)).toString();
		}
		title2.setText(t2);

		title.setToolTipText(t);
		title2.setToolTipText(t);
	}

	public void itemStateChanged(final ItemEvent ie) {
		Object source = ie.getSource();
		if (source.equals(c)) {
			if (ie.getStateChange() == ItemEvent.SELECTED) {
				d.setVisible(true);
			} else if (ie.getStateChange() == ItemEvent.DESELECTED) {
				// Prevent hiding when transforming
				if (Display.isTransforming(d)) {
					Utils.showStatus("Transforming! Can't change visibility.", false);
					c.setSelected(true);
					return;
				}
				d.setVisible(false);
			}
		} else if (source.equals(c_locked)) {
			if (ie.getStateChange() == ItemEvent.SELECTED) {
				// Prevent locking while transforming
				if (Display.isTransforming(d)) {
					Utils.logAll("Transforming! Can't lock.");
					c_locked.setSelected(false);
					return;
				}
				d.setLocked(true);
			} else if (ie.getStateChange() == ItemEvent.DESELECTED) {
				d.setLocked(false);
			}
		}
	}

	public void mousePressed(final MouseEvent me) {
		if (display.isTransforming()) return;
		display.select(d, me.isShiftDown());
		if (me.isPopupTrigger() || (ij.IJ.isMacOSX() && me.isControlDown()) || MouseEvent.BUTTON2 == me.getButton() || 0 != (me.getModifiers() & Event.META_MASK)) {
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

	protected void updateCheckboxes() {
		c.setSelected(d.isVisible());
		c_locked.setSelected(d.isLocked2());
	}
}
