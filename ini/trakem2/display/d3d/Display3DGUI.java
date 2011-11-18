package ini.trakem2.display.d3d;

import ij3d.Content;
import ij3d.Image3DUniverse;
import ij3d.ImageWindow3D;
import ij3d.UniverseListener;
import ini.trakem2.display.Display;
import ini.trakem2.display.Display3D;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.LayerSet;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.utils.IntegerField;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Component;
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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.media.j3d.BranchGroup;
import javax.media.j3d.Canvas3D;
import javax.media.j3d.View;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
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
		c.gridy = 1;
		c.insets = new Insets(10, 0, 0, 0);
		JPanel p2 = newPanelRemoveContents(this.univ);
		gb.setConstraints(p2, c);
		all.add(p2);
		
		// 3. Filterable selection list
		c.gridy = 2;
		c.weighty = 1;
		c.fill = GridBagConstraints.BOTH;
		JPanel p3 = newPanelFilterableTable(this.univ);
		gb.setConstraints(p3, c);
		all.add(p3);

		frame.getContentPane().add(all);
		return frame;
	}

	/** The {@link Image3DUniverse#getContents()} returns an unordered list, because the
	 * list are the values of a {@link Map}; this method circumvents that by getting
	 * the list from the enumeration of children elements in the scene of the {@param univ},
	 * which is a {@link BranchGroup}.
	 * 
	 * @param univ
	 * @return
	 */
	static private final List<Content> getOrderedContents(final Image3DUniverse univ) {
		final ArrayList<Content> cs = new ArrayList<Content>();
		final Enumeration<?> seq = univ.getScene().getAllChildren();
		while (seq.hasMoreElements()) {
			Object o = seq.nextElement();
			if (o instanceof Content) cs.add((Content)o);
		}
		return cs;
	}

	static private final void addTitledLineBorder(final JPanel p, final String title) {
		p.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.black, 1), title));
	}
	
	static private final class SliderTyperLink extends KeyAdapter {
		private final Image3DUniverse univ;
		private final JScrollBar slider;
		private final JTextField typer;
		SliderTyperLink(Image3DUniverse univ, JScrollBar slider, JTextField typer) {
			this.slider = slider;
			this.typer = typer;
			this.univ = univ;
		}
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
			typer.addKeyListener(new SliderTyperLink(univ, slider, typer));
			
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
		
		// Alpha slider
		c.gridx = 0;
		c.gridy += 1;
		JLabel aL = new JLabel("Alpha:");
		gb.setConstraints(aL, c);
		p.add(aL);
		
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		final JScrollBar alphaSlider = new JScrollBar(JScrollBar.HORIZONTAL, 255, 1, 0, 256);
		gb.setConstraints(alphaSlider, c);
		p.add(alphaSlider);
		
		c.gridx = 2;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		final JTextField alphaTyper = new IntegerField(255, 3);
		gb.setConstraints(alphaTyper, c);
		p.add(alphaTyper);
		
		alphaSlider.addAdjustmentListener(new AdjustmentListener() {
			@Override
			public void adjustmentValueChanged(AdjustmentEvent e) {
				Content content = univ.getSelected();
				if (null == content) {
					Utils.log("Nothing selected!");
					return;
				}
				float alpha = e.getValue() / 255.0f;
				content.setTransparency(1 - alpha);
				alphaTyper.setText(Integer.toString(e.getValue()));
			}
		});
		alphaTyper.addKeyListener(new SliderTyperLink(univ, alphaSlider, alphaTyper));
		
		// Button to colorize randomly
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
				
				// Alpha slider:
				alphaSlider.setValueIsAdjusting(true);
				int alpha = (int)((1 - arg0.getTransparency()) * 255);
				alphaTyper.setText(Integer.toString(alpha));
				alphaSlider.setValue(alpha);
				alphaSlider.setValueIsAdjusting(false);
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
		final ArrayList<Content> cs = new ArrayList<Content>(getOrderedContents(univ));
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
				for (final Content c : new ArrayList<Content>(getOrderedContents(univ))) {
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
	
	static private final class ContentTableModel extends AbstractTableModel
	{
		private List<Content> contents;
		private final Image3DUniverse univ;
		private final JTextField regexField;

		public ContentTableModel(final Image3DUniverse univ, final JTextField regexField) {
			this.univ = univ;
			this.regexField = regexField;
			this.contents = new ArrayList<Content>(getOrderedContents(univ));
		}

		@Override
		public int getRowCount() {
			return contents.size();
		}

		@Override
		public int getColumnCount() {
			return 2;
		}

		@Override
		public String getColumnName(int columnIndex) {
			switch(columnIndex) {
				case 0: return "nth";
				case 1: return "Name";
			}
			return null;
		}

		@Override
		public boolean isCellEditable(int rowIndex, int columnIndex) {
			return false;
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			switch (columnIndex) {
				case 0: return rowIndex + 1;
				case 1: return contents.get(rowIndex).getName();
			}
			return null;
		}

		@Override
		public void setValueAt(Object aValue, int rowIndex, int columnIndex) {}
		
		private void sortByName() {
			final TreeMap<String, Content> m = new TreeMap<String, Content>();
			for (final Content c : contents) {
				m.put(c.getName(), c);
			}
			// Swap
			this.contents = new ArrayList<Content>(m.values());
			fireTableDataChanged();
		}
		
		private void update(final ContentTable table) {
			Utils.invokeLater(new Runnable() { public void run() {
				final ArrayList<Content> cs = new ArrayList<Content>();
				try {
					final RegExFilter f = new RegExFilter(regexField.getText());
					for (Object ob : getOrderedContents(univ)) {
						Content c = (Content)ob;
						if (f.accept(c.getName())) {
							cs.add(c);
						}
					}
				} catch (PatternSyntaxException pse) {
					JOptionPane.showMessageDialog(univ.getWindow(), "Error parsing the regular expression:\n" + pse.getMessage());
					cs.addAll(getOrderedContents(univ));
				}
				ContentTableModel.this.contents = cs;
				fireTableDataChanged();
				// Adjust cell width:
				TableColumn tc = table.getColumnModel().getColumn(0);
				Component label = table.getDefaultRenderer(getColumnClass(0))
				.getTableCellRendererComponent(table, cs.size()-1, false, false, cs.size()-1, 0);
				tc.setMaxWidth(label.getBounds().width + 10); // 10 pixels of padding
			}});
		}
	}
	
	static private class ContentTable extends JTable {
		private static final long serialVersionUID = 1L;
		ContentTable(final Image3DUniverse univ) {
			super();
			getTableHeader().addMouseListener(new MouseAdapter() {
				public void mouseClicked(MouseEvent me) {
					if (2 != me.getClickCount()) return;
					int viewColumn = getColumnModel().getColumnIndexAtX(me.getX());
					int column = convertColumnIndexToModel(viewColumn);
					if (-1 == column) return;
					if (1 == column) {
						((ContentTableModel)getModel()).sortByName();
					}
				}
			});
			addMouseListener(new MouseAdapter() {
				public void mousePressed(MouseEvent me) {
					final int row = ContentTable.this.rowAtPoint(me.getPoint());
					if (2 == me.getClickCount()) {
						univ.select(((ContentTableModel)getModel()).contents.get(row));
					} else if (Utils.isPopupTrigger(me)) {
						List<Content> data = ((ContentTableModel)getModel()).contents;
						final ArrayList<Content> cs = new ArrayList<Content>();
						for (int i : getSelectedRows()) {
							cs.add(data.get(i));
						}
						JPopupMenu jp = new JPopupMenu();
						JMenuItem item = new JMenuItem("Select in 3D view");
						jp.add(item);
						item.addActionListener(new ActionListener() {
							@Override
							public void actionPerformed(ActionEvent e) {
								univ.select(((ContentTableModel)getModel()).contents.get(row));
							}
						});
						item = new JMenuItem("Select in TrakEM2");
						jp.add(item);
						item.addActionListener(new ActionListener() {
							@Override
							public void actionPerformed(ActionEvent e) {
								Utils.log("Selecting in TrakEM2:");
								Hashtable<LayerSet,Display3D> ht = Display3D.getMasterTable();
								LayerSet ls = null;
								for (final Map.Entry<LayerSet,Display3D> entry : ht.entrySet()) {
									if (entry.getValue().getUniverse() == univ) {
										ls = entry.getKey();
										break;
									}
								}
								if (null == ls) {
									Utils.log("Could not find an appropriate TrakEM2 project!");
									return;
								}
								Display front = Display.getFront();
								if (front.getLayerSet() != ls) {
									for (Display display : Display.getDisplays()) {
										if (display.getLayerSet() == ls) {
											front = display;
											break;
										}
									}
									if (front.getLayerSet() != ls) {
										Utils.log("Could not find an open display for the appropriate TrakEM2 project!");
										return;
									}
								}
								for (Content c : cs) {
									String name = c.getName();
									int start = name.lastIndexOf('#');
									if (-1 == start) {
										Utils.log("..skipped " + name);
										continue;
									}
									StringBuilder sb = new StringBuilder(10);
									start += 1;
									char ch;
									while (start < name.length() && Character.isDigit(ch = name.charAt(start))) {
										sb.append(ch);
										start += 1;
									}
									if (sb.length() > 0) {
										long id = Long.parseLong(sb.toString());
										DBObject dbo = ls.findById(id);
										if (null == dbo || !(dbo instanceof Displayable)) {
											Utils.log("Could not find an object with id #" + id);
											continue;
										}
										front.getSelection().add((Displayable)dbo);
										Utils.log("Selected: #" + id);
									} else {
										Utils.log("..skipped " + name);
										continue;
									}
								}
							}
						});
						item = new JMenuItem("Remove from 3D view");
						jp.add(item);
						item.addActionListener(new ActionListener() {
							@Override
							public void actionPerformed(ActionEvent e) {
								for (Content c : cs) {
									univ.removeContent(c.getName());
								}
							}
						});
						
						jp.show(ContentTable.this, me.getX(), me.getY());
					}
				}
			});
		}
		@Override
		public String getToolTipText(final MouseEvent me) {
			return ((ContentTableModel)getModel()).contents
				.get(ContentTable.this.rowAtPoint(me.getPoint())).getName();
		}
	}
	
	static private final class TableUniverseListener implements UniverseListener {
		private final ContentTable table;
		TableUniverseListener(final ContentTable table) {
			this.table = table;
		}
		@Override
		public void universeClosed() {}
		
		@Override
		public void transformationUpdated(View arg0) {}
		
		@Override
		public void transformationStarted(View arg0) {}
		
		@Override
		public void transformationFinished(View arg0) {}
		
		@Override
		public void contentSelected(final Content c) {
			Utils.invokeLater(new Runnable() { public void run() {
				int i = ((ContentTableModel)table.getModel()).contents.indexOf(c);
				table.getSelectionModel().setSelectionInterval(i, i);
			}});
		}
		
		@Override
		public void contentRemoved(Content arg0) {
			((ContentTableModel)table.getModel()).update(table);
		}
		
		@Override
		public void contentChanged(Content arg0) {
			((ContentTableModel)table.getModel()).update(table);
		}
		
		@Override
		public void contentAdded(Content arg0) {
			((ContentTableModel)table.getModel()).update(table);
		}
		@Override
		public void canvasResized() {}
	}
	
	static private final class RegExFilter {
		final Pattern pattern;
		RegExFilter(String regex) {
			if (0 == regex.length()) {
				this.pattern = null;
				return;
			}
			if (!regex.startsWith("^")) regex = "^.*" + regex;
			if (!regex.endsWith("$")) regex = regex + ".*$";
			this.pattern = Pattern.compile(regex);
		}
		final boolean accept(final String s) {
			if (null == pattern) return true;
			return pattern.matcher(s).matches();
		}
	}
	
	static private final JPanel newPanelFilterableTable(final Image3DUniverse univ) {
		JPanel p = new JPanel();
		p.setBackground(Color.white);
		GridBagConstraints c = new GridBagConstraints();
		GridBagLayout gb = new GridBagLayout();
		p.setLayout(gb);
		
		c.anchor = GridBagConstraints.NORTHWEST;
		c.fill = GridBagConstraints.HORIZONTAL;
		
		JLabel label = new JLabel("RegEx: ");
		final JTextField regexField = new JTextField();
		final ContentTable table = new ContentTable(univ);
		final ContentTableModel ctm = new ContentTableModel(univ, regexField);
		table.setModel(ctm);
		univ.addUniverseListener(new TableUniverseListener(table));
		
		regexField.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				ctm.update(table);
			}
		});
		
		gb.setConstraints(label, c);
		p.add(label);
		
		c.gridx = 1;
		c.weightx = 1;
		gb.setConstraints(regexField, c);
		p.add(regexField);
		
		c.gridx = 0;
		c.gridy = 1;
		c.gridwidth = 2;
		c.weighty = 1;
		c.fill = GridBagConstraints.BOTH;
		JScrollPane jsp = new JScrollPane(table);
		gb.setConstraints(jsp, c);
		p.add(jsp);		
		
		addTitledLineBorder(p, "Contents");
		return p;
	}
}
