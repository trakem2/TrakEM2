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


import javax.swing.*;
import java.awt.event.*;
import java.awt.Color;
import java.awt.Dimension;


public class Channel extends JPanel implements ItemListener, MouseListener {

	private static final long serialVersionUID = 1L;

	static public final int HEIGHT = 52;

	private JCheckBox c;

	private Display display;

	static public final int MONO = 1;
	static public final int RED  = 2;
	static public final int GREEN= 4;
	static public final int BLUE = 8;
	private int channel;
	private float alpha = 1.0f;

	Channel(Display display, int channel) {
		this.display = display;
		this.channel = channel;
		this.c = new JCheckBox();
		this.c.setSelected(true);
		this.c.addItemListener(this);
		String t = "mono";
		switch (channel) {
			case RED: t = "red"; break;
			case GREEN: t = "green"; break;
			case BLUE: t = "blue"; break;
			default: t = "mono"; break;
		}
		JLabel title = new JLabel("   " + t);
		title.addMouseListener(this);
		setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
		add(c);
		add(title);

		Dimension dim = new Dimension(250, HEIGHT);
		setMinimumSize(dim);
		setMaximumSize(dim);

		addMouseListener(this);
		setBackground(Color.white);
		setBorder(BorderFactory.createLineBorder(Color.black));
	}

	/** For reconstructing purposes. */
	public void setAlpha(float alpha, boolean selected) {
		if (alpha != this.alpha && alpha >= 0.0f && alpha <= 1.0f) {
			this.alpha = alpha;
		}
		c.setSelected(selected);
	}

	public void setAlpha(float alpha) {
		if (alpha != this.alpha && alpha >= 0.0f && alpha <= 1.0f) {
			this.alpha = alpha;
			display.setChannel(channel, alpha);
		}
	}

	public float getAlpha() { return alpha; }

	public void itemStateChanged(ItemEvent ie) {
		Object source = ie.getSource();
		if (source.equals(c)) {
			if (ie.getStateChange() == ItemEvent.SELECTED) {
				display.setChannel(channel, alpha);
			} else if (ie.getStateChange() == ItemEvent.DESELECTED) {
				display.setChannel(channel, 0.0f);
			}
		}
	}

	public boolean isSelected() {
		return c.isSelected();
	}

	public void setActive(boolean active) {
		if (active) {
			setBackground(Color.cyan);
		} else {
			setBackground(Color.white);
		}
	}

	public boolean isActive() {
		return getBackground().equals(Color.cyan);
	}

	public void setSelected (boolean s) {
		c.setSelected(s);
	}

	public void mousePressed(MouseEvent me) {
		display.setActiveChannel(this);
	}

	public void mouseReleased(MouseEvent me) {}
	public void mouseEntered(MouseEvent me) {}
	public void mouseExited (MouseEvent me) {}
	public void mouseClicked(MouseEvent me) {}
}
