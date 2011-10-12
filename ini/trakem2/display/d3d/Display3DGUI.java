package ini.trakem2.display.d3d;

import ij3d.Content;
import ij3d.Image3DUniverse;
import ij3d.ImageWindow3D;
import ij3d.UniverseListener;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.media.j3d.Canvas3D;
import javax.media.j3d.View;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.vecmath.Color3f;

public class Display3DGUI {
	
	private final Image3DUniverse univ;
	
	public Display3DGUI(final Image3DUniverse univ) {
		this.univ = univ;
	}
	
	public Image3DUniverse getUniverse() {
		return univ;
	}

	public ImageWindow3D init() {
		// Extract the Canvas3D from the ImageWindow3D
		final ImageWindow3D frame = new ImageWindow3D("TrakEM2 3D Display", this.univ);
		frame.getContentPane().removeAll();
		
		// New layout
		final JPanel all = new JPanel();
		all.setBackground(Color.white);
		all.setPreferredSize(new Dimension(768, 512));
		final GridBagConstraints c = new GridBagConstraints();
		final GridBagLayout gb = new GridBagLayout();
		all.setLayout(gb);
		
		// Add Canvas3D
		final Canvas3D canvas = this.univ.getCanvas();
		c.anchor = GridBagConstraints.NORTHWEST;
		c.fill = GridBagConstraints.BOTH;
		c.weightx = 1;
		c.weighty = 1;
		c.gridheight = 4;
		gb.setConstraints(canvas, c);
		all.add(canvas);

		// 1. Panel to edit color and assign random colors
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 0;
		c.weighty = 0;
		c.gridheight = 1;
		c.gridx = 1;
		JPanel p1 = newPanelColors(this.univ);
		gb.setConstraints(p1, c);
		all.add(p1);

		// 2. Panel to delete all whose name matches a regex
		c.gridy += 1;
		JPanel p2 = newPanelRemoveContents(this.univ);
		gb.setConstraints(p2, c);
		all.add(p2);
		
		// 3. Filterable selection list
		c.gridy += 1;
		JPanel p3 = newPanelFilterableListing(this.univ);
		gb.setConstraints(p3, c);
		all.add(p3);
		
		// 4. Padding panel
		JPanel empty = new JPanel();
		empty.setBackground(Color.white);
		c.gridy += 1;
		c.fill = GridBagConstraints.BOTH;
		c.weighty = 1;
		gb.setConstraints(empty, c);
		all.add(empty);

		frame.getContentPane().add(all);
		return frame;
	}

	static private final void addTitledLineBorder(final JPanel p, final String title) {
		p.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.black, 1), title));
	}

	static private final JPanel newPanelColors(final Image3DUniverse univ) {
		JPanel p = new JPanel();
		p.setBackground(Color.white);
		GridBagLayout gb = new GridBagLayout();
		GridBagConstraints c = new GridBagConstraints();
		c.anchor = GridBagConstraints.NORTHWEST;
		c.fill = GridBagConstraints.HORIZONTAL;
		p.setLayout(gb);
		final String[] labels = new String[]{"Red", "Green", "Blue"};
		final JScrollBar[] sliders = new JScrollBar[3];
		final JTextField[] typers = new JTextField[3];
		for (int i=0; i<3; ++i) {
			final JScrollBar slider = new JScrollBar(JScrollBar.HORIZONTAL, 255, 1, 0, 256);
			sliders[i] = slider;
			final JTextField typer = new IntegerField(255, 3);
			typers[i] = typer;
			final int k = i;
			slider.addAdjustmentListener(new AdjustmentListener() {
				@Override
				public void adjustmentValueChanged(AdjustmentEvent e) {
					Content content = univ.getSelected();
					if (null == content) {
						Utils.log("Nothing selected!");
						return;
					}
					Color3f color = content.getColor();
					if (null == color) color = new Color3f(1, 1, 0); // default to yellow
					float[] co = new float[3];
					color.get(co);
					co[k] = e.getValue() / 255.0f;
					content.setColor(new Color3f(co));
					typer.setText(Integer.toString(e.getValue()));
				}
			});
			typer.addKeyListener(new KeyAdapter() {
				@Override
				public void keyPressed(KeyEvent ke) {
					String txt = typer.getText();
					if (txt.length() > 0) {
						int val = Integer.parseInt(txt);
						slider.setValue(val);
						Content content = univ.getSelected();
						if (null != content) {
							slider.setValue(val); // will also set the color
						}
					}
				}
			});
			
			final JLabel l = new JLabel(labels[i]);
			
			// Layout
			c.gridx = 0;
			c.gridy = i;
			gb.setConstraints(l, c);
			p.add(l);
			
			c.gridx = 1;
			c.weightx = 1;
			c.fill = GridBagConstraints.HORIZONTAL;
			gb.setConstraints(slider, c);
			p.add(slider);
			
			c.gridx = 2;
			c.weightx = 0;
			c.fill = GridBagConstraints.NONE;
			gb.setConstraints(typer, c);
			p.add(typer);
		}

		c.gridx = 0;
		c.gridy += 1;
		c.gridwidth = 3;
		c.weightx = 1;
		c.insets = new Insets(15, 4, 4, 4);
		JButton r = new JButton("Assign random colors to all");
		r.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				randomizeColors(univ);
			}
		});
		gb.setConstraints(r, c);
		p.add(r);
		
		addTitledLineBorder(p, "Colors");
		
		univ.addUniverseListener(new UniverseListener() {
			
			@Override
			public void universeClosed() {}
			
			@Override
			public void transformationUpdated(View arg0) {}
			
			@Override
			public void transformationStarted(View arg0) {}
			
			@Override
			public void transformationFinished(View arg0) {}
			
			@Override
			public void contentSelected(Content arg0) {
				if (null == arg0) {
					return;
				}
				Color3f color = arg0.getColor();
				if (null == color) color = new Color3f(1, 1, 0); // default to yellow
				float[] co = new float[3];
				color.get(co);
				for (int i=0; i<3; ++i) {
					// Disallow the slider from firing an event when its value is adjusted
					sliders[i].setValueIsAdjusting(true);
					int val = (int)(co[i] * 255);
					typers[i].setText(Integer.toString(val));
					sliders[i].setValue(val);
				}
				// After all are set, re-enable, which triggers events (the color will be set three times...)
				for (int i=0; i<3; ++i) {
					sliders[i].setValueIsAdjusting(false);
				}
			}

			@Override
			public void contentRemoved(Content arg0) {}
			
			@Override
			public void contentChanged(Content arg0) {
				if (arg0 == univ.getSelected()) {
					contentSelected(arg0);
				}
			}

			@Override
			public void contentAdded(Content arg0) {}
			
			@Override
			public void canvasResized() {}
		});

		return p;
	}

	static public final class IntegerField extends JTextField {
		private static final long serialVersionUID = 1L;
		private final int maxDigits;
		public IntegerField(int initial, int maxDigits) {
			super();
			this.maxDigits = maxDigits;
			String text = Integer.toString(initial);
			if (text.length() > maxDigits) text = text.substring(0, maxDigits);
			setText(text);
		}
		@Override
		protected void processKeyEvent(KeyEvent ke) {
			if (0 != ke.getModifiers()) return;
			if (ke.getID() != KeyEvent.KEY_PRESSED) return;
			switch (ke.getKeyCode()) {
				case KeyEvent.VK_DELETE:
				case KeyEvent.VK_BACK_SPACE:
				case KeyEvent.VK_ENTER:
				case KeyEvent.VK_LEFT:
				case KeyEvent.VK_RIGHT:
					super.processKeyEvent(ke);
					for (KeyListener kl : getKeyListeners()) {
						kl.keyPressed(ke);
					}
					return;
			}
			// For keyPressed only:
			if (Character.isDigit(ke.getKeyChar())) {
				if (getText().length() < maxDigits) {
					setText(getText() + ke.getKeyChar());
					for (KeyListener kl : getKeyListeners()) {
						kl.keyPressed(ke);
					}
				}
			}
		}
		@Override
		public String getText() {
			final String text = super.getText();
			if (0 == text.length()) return "0";
			return text;
		}
	}
	
	static private final float[][] colors = new float[][]{
		new float[]{255, 255, 0},  // yellow
		new float[]{255, 0, 0},    // red
		new float[]{255, 0, 255},  // magenta
		new float[]{0, 255, 0},    // blue
		new float[]{0, 255, 255},  // cyan
		new float[]{0, 255, 0},    // green
		new float[]{255, 255, 255},// white
		new float[]{255, 128, 0},  // orange
		new float[]{255, 0, 128},
		new float[]{128, 255, 0},
		new float[]{128, 0, 255},
		new float[]{0, 255, 128},
		new float[]{0, 128, 255},
		new float[]{128, 128, 128},// grey
	};
	
	static public final void randomizeColors(final Image3DUniverse univ) {
		final ArrayList<Content> cs = new ArrayList<Content>(univ.getContents());
		for (int i=0; i<cs.size(); ++i) {
			if (i < colors.length) {
				cs.get(i).setColor(new Color3f(colors[i]));
			} else {
				cs.get(i).setColor(new Color3f((float)Math.random(), (float)Math.random(), (float)Math.random()));
			}
		}
		// Update the color bars if something is selected:
		Content content = univ.getSelected();
		if (null != content) univ.fireContentChanged(content);
	}
	
	static private final JPanel newPanelRemoveContents(final Image3DUniverse univ) {
		JPanel p = new JPanel();
		p.setBackground(Color.white);
		GridBagLayout gb = new GridBagLayout();
		GridBagConstraints c = new GridBagConstraints();
		c.anchor = GridBagConstraints.SOUTHWEST;
		c.fill = GridBagConstraints.HORIZONTAL;
		p.setLayout(gb);
		
		JLabel label = new JLabel("RegEx:");
		final JTextField regex = new JTextField();
		JButton remove = new JButton("X");
		ActionListener a = new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				String s = regex.getText();
				if (0 == s.length()) return;
				if (!s.startsWith("^")) s = "^.*" + s;
				if (!s.endsWith("$")) s = s + ".*$";
				Pattern pattern = null;
				try {
					pattern = Pattern.compile(s);
				} catch (PatternSyntaxException pse) {
					JOptionPane.showMessageDialog(univ.getWindow(), "Error parsing the regular expression:\n" + pse.getMessage());
					return;
				}
				for (final Content c : new ArrayList<Content>(univ.getContents())) {
					if (pattern.matcher(c.getName()).matches()) {
						univ.removeContent(c.getName());
						Utils.log("Removed " + c.getName());
					}
				}
			}
		};
		remove.addActionListener(a);
		regex.addActionListener(a);
		
		gb.setConstraints(label, c);
		p.add(label);
		
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.BOTH;
		gb.setConstraints(regex, c);
		p.add(regex);
		
		c.gridx = 2;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		gb.setConstraints(remove, c);
		p.add(remove);
		
		addTitledLineBorder(p, "Remove content");
		
		return p;
	}
	
	static private final JPanel newPanelFilterableListing(final Image3DUniverse univ) {
		JPanel p = new JPanel();
		p.setBackground(Color.white);
		GridBagConstraints c = new GridBagConstraints();
		GridBagLayout gb = new GridBagLayout();
		p.setLayout(gb);
		
		c.anchor = GridBagConstraints.NORTHWEST;
		c.fill = GridBagConstraints.HORIZONTAL;
		
		JTable table;
		// TODO columns: index, title, color. Then add ways to sort by name and color,
		// and also a text field on top to search for a subset of the possible elements.
		// When an object is selected and present in the list, its row is highlighted.
		
		addTitledLineBorder(p, "Contents");
		return p;
	}
}
