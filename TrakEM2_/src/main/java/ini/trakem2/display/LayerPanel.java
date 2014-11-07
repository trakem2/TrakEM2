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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Event;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JSlider;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;


public final class LayerPanel extends JPanel implements MouseListener {

	private static final long serialVersionUID = 1L;
	private final JLabel title;
	protected final JSlider slider = new JSlider(javax.swing.SwingConstants.HORIZONTAL, 0, 100, 0);
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
				display.getCanvas().repaint(true);
			}
		});

		// Insert this mouse listener before the slider's
		/* // BIZARRE autoscrolling of the slider when popup is up. And cannot be fixed with if (popup), neither within the ChangeEvent (which doesn't register)
		final MouseListener[] mls = slider.getMouseListeners();
		for (final MouseListener ml : mls) slider.removeMouseListener(ml); // works because getMouseListeners returns an immutable array.
		slider.addMouseListener(new MouseAdapter() {
			public void mousePressed(MouseEvent me) {
				doMousePressed(me);
				me.consume();
			}
		});
		for (final MouseListener ml : mls) slider.addMouseListener(ml);
		*/

		this.title = new JLabel(makeTitle());
		this.title.addMouseListener(this);

		GridBagLayout gb = new GridBagLayout();
		setLayout(gb);
		GridBagConstraints c = new GridBagConstraints();
		c.anchor = GridBagConstraints.NORTHWEST;

		c.fill = GridBagConstraints.HORIZONTAL;
		c.weighty = 0;
		gb.setConstraints(title, c);
		add(title);
		
		JPanel empty = new JPanel();
		c.gridy += 1;
		c.fill = GridBagConstraints.BOTH;
		c.weighty = 1;
		gb.setConstraints(empty, c);
		
		c.gridy += 1;
		c.weighty = 0;
		c.fill = GridBagConstraints.HORIZONTAL;
		gb.setConstraints(slider, c);
		add(slider);

		setMinimumSize(new Dimension(0, DisplayablePanel.HEIGHT));
		setPreferredSize(new Dimension(250, DisplayablePanel.HEIGHT));

		addMouseListener(this);
		setBackground(this.color);
		setBorder(BorderFactory.createLineBorder(Color.black));
	}

	private final String makeTitle() {
		return new StringBuilder().append(layer.getParent().indexOf(layer) + 1).append(':').append(' ').append(layer.getTitle()).toString();
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

	private class ColorActionListener implements ActionListener {
		final Color c;
		ColorActionListener(final Color c) { this.c = c; }
		public void actionPerformed(final ActionEvent ae) {
			setColor(c);
			display.setColorChannel(layer, c);
		}
	}

	public void mousePressed(final MouseEvent me) {
		doMousePressed(me);
	}

	private void doMousePressed(final MouseEvent me) {
		if (Utils.isPopupTrigger(me)) {
			JPopupMenu popup = new JPopupMenu();
			JMenuItem item = new JMenuItem("Set as red channel"); popup.add(item);
			if (Color.red == this.color) item.setEnabled(false);
			else item.addActionListener(new ColorActionListener(Color.red));
			item = new JMenuItem("Set as blue channel"); popup.add(item);
			if (Color.blue == this.color) item.setEnabled(false);
			else item.addActionListener(new ColorActionListener(Color.blue));
			item = new JMenuItem("Reset"); popup.add(item);
			if (Color.white == this.color) item.setEnabled(false);
			else item.addActionListener(new ColorActionListener(Color.white));
			JCheckBoxMenuItem citem = new JCheckBoxMenuItem("Invert"); popup.add(citem);
			citem.setState(display.invert_colors);
			citem.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent ae) {
					display.invert_colors = !display.invert_colors;
					display.getCanvas().repaint(true);
				}
			});
			JMenu transp_menu = new JMenu("Overlay items"); popup.add(transp_menu);
			final JCheckBoxMenuItem cbI = new JCheckBoxMenuItem("Images", display.transp_overlay_images);
			final JCheckBoxMenuItem cbA = new JCheckBoxMenuItem("Areas", display.transp_overlay_areas);
			final JCheckBoxMenuItem cbL = new JCheckBoxMenuItem("Text labels", display.transp_overlay_text_labels);
			ActionListener lis = new ActionListener() {
				public void actionPerformed(ActionEvent ae) {
					Object src = ae.getSource();
					if (src == cbI) display.setTranspOverlayImages(cbI.getState()); 
					else if (src == cbA) display.setTranspOverlayAreas(cbA.getState());
					else if (src == cbL) display.setTranspOverlayTextLabels(cbL.getState());
				}
			};
			for (JCheckBoxMenuItem rb : new JCheckBoxMenuItem[]{cbI, cbA, cbL}) {
				transp_menu.add(rb);
				rb.addActionListener(lis);
			}
	
			popup.addSeparator();
			JMenu composites = new JMenu("Composite mode");
			ButtonGroup group = new ButtonGroup();
			byte compositeMode = display.getLayerCompositeMode(layer);
			for (int i=0; i<Displayable.compositeModes.length; i++) {
				JRadioButton rb = new JRadioButton(Displayable.compositeModes[i], compositeMode == i);
				rb.setActionCommand(Displayable.compositeModes[i]);
				final byte cm = (byte) i;
				rb.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent ae) {
						display.setLayerCompositeMode(layer, cm);
					}
				});
				composites.add(rb);
				group.add(rb);
			}
			popup.add(composites);
			popup.addSeparator();
			item = new JMenuItem("Reset all layer coloring"); popup.add(item);
			item.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent ae) {
					display.resetLayerColors();
				}
			});
			item = new JMenuItem("Reset all layer alphas"); popup.add(item);
			item.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent ae) {
					display.resetLayerAlphas();
				}
			});
			item = new JMenuItem("Reset all layer composites"); popup.add(item);
			item.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent ae) {
					display.resetLayerComposites();
				}
			});
			popup.show(this, me.getX(), me.getY());
			me.consume();
			return;
		}

		final int mod = me.getModifiers();
		Utils.log2("mouse pressed : " + mod);
		if (0 == (mod & Event.ALT_MASK) && 0 == (mod & Event.SHIFT_MASK)) {
			// Would mess up translation of red/blue colors when scrolling
			// So just do nothing.
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
			display.setColorChannel(this.layer, this.color);
			me.consume();
		} else if (0 != (mod & Event.SHIFT_MASK)) {
			if (this.color == Color.red) {
				// unset
				setColor(Color.white);
			} else {
				// set as red channel
				setColor(Color.red);
			}
			display.setColorChannel(this.layer, this.color);
			me.consume();
		}
	}

	public void mouseReleased(MouseEvent me) {}
	public void mouseEntered(MouseEvent me) {}
	public void mouseExited (MouseEvent me) {}
	public void mouseClicked(MouseEvent me) {}

	public final String toString() {
		return "Layer panel for " + layer.getTitle();
	}
}
