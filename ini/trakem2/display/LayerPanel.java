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

import ij.IJ;
import ini.trakem2.utils.Utils;

import javax.swing.BoxLayout;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.Event;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseListener;
import java.awt.event.MouseEvent;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Dimension;


public final class LayerPanel extends JPanel implements MouseListener {

	private final JLabel title;
	private final JSlider slider = new JSlider(javax.swing.SwingConstants.HORIZONTAL, 0, 100, 0);
	private Color color = Color.white;
	private float alpha = 0.0f; // for overlays

	private final Display display;
	protected final Layer layer;

	public LayerPanel(final Display display, final Layer layer) {
		this.display = display;
		this.layer = layer;

		this.slider.addChangeListener(new ChangeListener() {
			public void stateChanged(final ChangeEvent ce) {
				final float a = slider.getValue() / 100.0f;
				setAlpha(a);
				display.storeLayerAlpha(LayerPanel.this, a);
				display.repaint();
			}
		});

		this.title = new JLabel(makeTitle());
		this.title.addMouseListener(this);

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		add(title);
		add(slider);

		final Dimension dim = new Dimension(250 - Display.scrollbar_width, DisplayablePanel.HEIGHT);
		setMinimumSize(dim);
		setMaximumSize(dim);
		//setPreferredSize(dim);

		addMouseListener(this);
		setBackground(this.color);
		setBorder(BorderFactory.createLineBorder(Color.black));
	}

	private final String makeTitle() {
		return new StringBuffer().append(layer.getParent().indexOf(layer) + 1).append(':').append(' ').append(layer.getTitle()).toString();
	}

	public final void setColor(final Color color) {
		this.color = color;
		setBackground(color);
		slider.setBackground(color);
		if (Color.white == color) {
			title.setForeground(Color.black);
		} else {
			title.setForeground(Color.white);
		}
		repaint();
	}

	public final Color getColor() { return color; }

	public final void setAlpha(final float alpha) {
		if (alpha < 0 || alpha > 1) return;
		this.alpha = alpha;
		slider.setValue((int)(alpha * 100));
	}

	public final float getAlpha() { return alpha; }

	public final void paint(final Graphics g) {
		title.setText(makeTitle());
		if (display.getLayer() == layer) {
			setBackground(Color.green);
			slider.setBackground(Color.green);
		} else {
			setBackground(color);
			slider.setBackground(color);
		}
		super.paint(g);
	}

	public void mousePressed(final MouseEvent me) {
		if (Utils.isPopupTrigger(me)) {
			JPopupMenu popup = new JPopupMenu();
			JMenuItem item = new JMenuItem("Reset layer coloring");
			item.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent ae) {
					display.resetLayerColors();
				}
			});
			popup.add(item);
			item = new JMenuItem("Reset all layer alphas");
			item.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent ae) {
					display.resetLayerAlphas();
				}
			});
			popup.add(item);
			popup.show(this, me.getX(), me.getY());
			return;
		}

		final int mod = me.getModifiers();
		Utils.log2("mouse pressed : " + mod);
		if (0 == (mod & Event.ALT_MASK) && 0 == (mod & Event.SHIFT_MASK)) {
			// Would mess up translation of red/blue colors when scrolling
			/*
			display.toLayer(this.layer);
			setColor(Color.white);
			display.setColorChannel(this.layer, this.color);
			repaint();
			*/
		} else if (display.getLayer() == this.layer) {
			// do nothing
		} else if (0 != (mod & Event.ALT_MASK)) {
			if (this.color == Color.blue) {
				// unset
				setColor(Color.white);
			} else {
				// set as blue channel
				setColor(Color.blue);
			}
		} else if (0 != (mod & Event.SHIFT_MASK)) {
			if (this.color == Color.red) {
				// unset
				setColor(Color.white);
			} else {
				// set as red channel
				setColor(Color.red);
			}
		}
		display.setColorChannel(this.layer, this.color);
	}

	public void mouseReleased(MouseEvent me) {}
	public void mouseEntered(MouseEvent me) {}
	public void mouseExited (MouseEvent me) {}
	public void mouseClicked(MouseEvent me) {}

	public final String toString() {
		return "Layer panel for " + layer.getTitle();
	}
}
