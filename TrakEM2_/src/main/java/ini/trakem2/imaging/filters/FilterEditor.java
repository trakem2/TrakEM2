package ini.trakem2.imaging.filters;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.gui.YesNoCancelDialog;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.AbstractTableModel;

public class FilterEditor {

	// They are all IFilter, and all have protected fields.
	@SuppressWarnings("rawtypes")
	static public final Class[] available =
		new Class[]{CLAHE.class, NormalizeLocalContrast.class, EqualizeHistogram.class,
					EnhanceContrast.class, ResetMinAndMax.class, DefaultMinAndMax.class,
					GaussianBlur.class, Invert.class, Normalize.class,
		            RankFilter.class, RobustNormalizeLocalContrast.class,
		            ValueToNoise.class, SubtractBackground.class,
		            CorrectBackground.class,
		            LUTRed.class, LUTGreen.class, LUTBlue.class,
		            LUTMagenta.class, LUTCyan.class, LUTYellow.class,
		            LUTOrange.class, LUTCustom.class};

	static private class TableAvailableFilters extends JTable {
		public TableAvailableFilters(final TableChosenFilters tcf) {
			setModel(new AbstractTableModel() {
				@Override
				public Object getValueAt(final int rowIndex, final int columnIndex) {
					return available[rowIndex].getSimpleName();
				}
				@Override
				public int getRowCount() {
					return available.length;
				}
				@Override
				public int getColumnCount() {
					return 1;
				}
				@Override
				public String getColumnName(final int col) {
					return "Available Filters";
				}
			});
			addMouseListener(new MouseAdapter() {
				@Override
				public void mousePressed(final MouseEvent me) {
					if (2 == me.getClickCount()) {
						tcf.add(available[getSelectedRow()]);
					}
				}
			});
		}
	}

	static private class FilterWrapper {
		IFilter filter;
		final Field[] fields;
		FilterWrapper(final Class<?> filterClass) {
			this.fields = filterClass.getDeclaredFields();
			try {
				this.filter = (IFilter) filterClass.getConstructor().newInstance();
			} catch (final Exception e) {
				e.printStackTrace();
			}
		}
		/** Makes a copy of {@param filter}. */
		FilterWrapper(final IFilter filter) {
			this.fields = filter.getClass().getDeclaredFields();
			try {
				// Create a copy and set its parameters to the same values
				this.filter = (IFilter) filter.getClass().getConstructor().newInstance();
				for (final Field f : fields) {
					f.set(this.filter, f.get(filter));
				}
			} catch (final Exception e) {
				e.printStackTrace();
			}
		}
		FilterWrapper() {
			this.fields = new Field[0]; // empty
		}
		String name(final int i) {
			return fields[i].getName();
		}
		String value(final int i) {
			try {
				return "" + fields[i].get(filter);
			} catch (final Exception e) {
				e.printStackTrace();
			}
			return null;
		}
		void set(final int i, Object v) {
			Utils.log2("v class: " + v.getClass());
			final Field f = fields[i];
			f.setAccessible(true);
			final Class<?> c = f.getType();
			try {
				final String sv = v.toString().trim();
				Utils.log2("sv is: " + sv + ", and f.getDeclaringClass() == " + c);
				if (Double.TYPE == c) v = Double.parseDouble(sv);
				else if (Float.TYPE == c) v = Float.parseFloat(sv);
				else if (Integer.TYPE == c) v = Integer.parseInt(sv);
				else if (Boolean.TYPE == c) v = Boolean.parseBoolean(sv);
				else if (Long.TYPE == c) v = Long.parseLong(sv);
				else if (Short.TYPE == c) v = Short.parseShort(sv);
				else if (Byte.TYPE == c) v = Byte.parseByte(sv);
				else if (String.class == c) v = sv;
				//
				f.set(filter, v);
			} catch (final Exception e) {
				Utils.logAll("New value '" + v + "' is invalid; keeping the last value.");
				e.printStackTrace();
			}
		}
		public boolean sameParameterValues(final FilterWrapper w) {
			if (this.filter == w.filter) return true;
			if (filter.getClass() != w.filter.getClass()) return false;
			for (int i=0; i<fields.length; ++i) {
				if (!value(i).equals(w.value(i))) {
					return false;
				}
			}
			return true;
		}
	}

	static private class TableChosenFilters extends JTable {
		private final ArrayList<FilterWrapper> filters;
		public TableChosenFilters(final ArrayList<FilterWrapper> filters) {
			this.filters = filters;
			setModel(new AbstractTableModel() {
				@Override
				public Object getValueAt(final int rowIndex, final int columnIndex) {
					switch (columnIndex) {
					case 0: return rowIndex + 1;
					case 1: return filters.get(rowIndex).filter.getClass().getSimpleName();
					}
					return null;
				}
				@Override
				public int getRowCount() {
					return filters.size();
				}
				@Override
				public int getColumnCount() {
					return 2;
				}
				@Override
				public String getColumnName(final int col) {
					switch (col) {
						case 0: return "";
						case 1: return "Chosen Filters";
					}
					return null;
				}
			});
			addMouseListener(new MouseAdapter() {
				@Override
				public void mousePressed(final MouseEvent me) {
					if (me.getClickCount() == 2) {
						int row = getSelectedRow();
						filters.remove(row);
						((AbstractTableModel)getModel()).fireTableStructureChanged();
						if (filters.size() > 0) {
							if (row > 0) --row;
							getSelectionModel().setSelectionInterval(row, row);
						}
						getColumnModel().getColumn(0).setMaxWidth(10);
					}
				}
			});
			addKeyListener(new KeyAdapter() {
				@Override
				public void keyPressed(final KeyEvent ke) {
					// Check preconditions
					final int row = getSelectedRow();
					if (-1 == row) return;
					int selRow = -1;
					//
					switch (ke.getKeyCode()) {
					case KeyEvent.VK_PAGE_UP:
						if (filters.size() > 1 && row > 0) {
							filters.add(row -1, filters.remove(row));
							selRow = row -1;
							ke.consume();
						}
						break;
					case KeyEvent.VK_PAGE_DOWN:
						if (filters.size() > 1 && row < filters.size() -1) {
							filters.add(row + 1, filters.remove(row));
							selRow = row + 1;
							ke.consume();
						}
						break;
					case KeyEvent.VK_DELETE:
						if (filters.size() > 1) {
							if (0 == row) selRow = 0;
							else if (filters.size() -1 == row) selRow = filters.size() -2;
							else selRow = row -1;
						}
						filters.remove(row);
						ke.consume();
						break;
					case KeyEvent.VK_UP:
						selRow = row > 0 ? row -1 : row;
						ke.consume();
						break;
					case KeyEvent.VK_DOWN:
						selRow = row < filters.size() -1 ? row + 1 : row;
						ke.consume();
						break;
					}
					((AbstractTableModel)getModel()).fireTableStructureChanged();
					getColumnModel().getColumn(0).setMaxWidth(10);
					if (-1 != selRow) {
						getSelectionModel().setSelectionInterval(selRow, selRow);
					}
				}
			});
			getColumnModel().getColumn(0).setMaxWidth(10);
		}
		final FilterWrapper selected() {
			final int row = getSelectedRow();
			if (-1 == row) return new FilterWrapper(); // empty
			return filters.get(row);
		}
		final void add(final Class<?> filterClass) {
			filters.add(new FilterWrapper(filterClass));
			((AbstractTableModel)getModel()).fireTableStructureChanged();
			this.getSelectionModel().setSelectionInterval(filters.size()-1, filters.size()-1);
		}
		final void setupListener(final TableParameters tp) {
			getSelectionModel().addListSelectionListener(new ListSelectionListener() {
				@Override
				public void valueChanged(final ListSelectionEvent e) {
					tp.triggerUpdate();
				}
			});
		}
	}

	static private class TableParameters extends JTable {
		TableParameters(final TableChosenFilters tcf) {
			setModel(new AbstractTableModel() {
				@Override
				public Object getValueAt(final int rowIndex, final int columnIndex) {
					final FilterWrapper w = tcf.selected();
					switch (columnIndex) {
					case 0:
						return w.name(rowIndex);
					case 1:
						return w.value(rowIndex);
					}
					return null;
				}
				@Override
				public int getRowCount() {
					return tcf.selected().fields.length;
				}

				@Override
				public int getColumnCount() {
					return 2;
				}

				@Override
				public String getColumnName(final int col) {
					switch (col) {
					case 0: return "Parameter";
					case 1: return "Value";
					default:
						return null;
					}
				}

				@Override
				public void setValueAt(final Object v, final int rowIndex, final int columnIndex) {
					tcf.selected().set(rowIndex, v);
			    }

				@Override
				public boolean isCellEditable(final int rowIndex, final int columnIndex) {
					return 1 == columnIndex;
				}
			});
		}
		void triggerUpdate() {
			((AbstractTableModel)getModel()).fireTableStructureChanged();
		}
	}

	static public final void GUI(final Collection<Patch> patches, final Patch reference) {
		final ArrayList<FilterWrapper> filters = new ArrayList<FilterWrapper>();

		// Find out if all images have the exact same filters
		final StringBuilder sb = new StringBuilder();
		final Patch ref = (null == reference? patches.iterator().next() : reference);
		final IFilter[] refFilters = ref.getFilters();
		if (null != refFilters) {
			for (final IFilter f : refFilters) filters.add(new FilterWrapper(f)); // makes a copy of the IFilter
		}
		//
		for (final Patch p : patches) {
			if (ref == p) continue;
			final IFilter[] fs = p.getFilters();
			if (null == fs && null == refFilters) continue; // ok
			if ((null != refFilters && null == fs)
			 || (null == refFilters && null != fs)
			 || (null != refFilters && null != fs && fs.length != refFilters.length)) {
				sb.append("WARNING: patch #" + p.getId() + " has a different number of filters than reference patch #" + ref.getId());
				continue;
			}
			// Compare each
			for (int i=0; i<refFilters.length; ++i) {
				if (fs[i].getClass() != refFilters[i].getClass()) {
					sb.append("WARNING: patch #" + p.getId() + " has a different filters than reference patch #" + ref.getId());
					break;
				}
				// Does the filter have the same parameters?
				if (!filters.get(i).sameParameterValues(new FilterWrapper(fs[i]))) {
					sb.append("WARNING: patch #" + p.getId() + " has filter '" + fs[i].getClass().getSimpleName() + "' with different parameters than the reference patch #" + ref.getId());
				}
			}
		}
		if (sb.length() > 0) {
			final GenericDialog gd = new GenericDialog("WARNING", null == Display.getFront() ? IJ.getInstance() : Display.getFront().getFrame());
			gd.addMessage("Filters are not all the same for all images:");
			gd.addTextAreas(sb.toString(), null, 20, 30);
			final String[] s = new String[]{"Use the filters of the reference image", "Start from an empty list of filters"};
			gd.addChoice("Do:", s, s[0]);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			if (1 == gd.getNextChoiceIndex()) filters.clear();
		}

		final TableChosenFilters tcf = new TableChosenFilters(filters);
		final TableParameters tp = new TableParameters(tcf);
		tcf.setupListener(tp);
		final TableAvailableFilters taf = new TableAvailableFilters(tcf);

		if (filters.size() > 0) {
			tcf.getSelectionModel().setSelectionInterval(0, 0);
		}

		final JFrame frame = new JFrame("Image filters");
		final JButton set = new JButton("Set");
		final JComboBox pulldown = new JComboBox(new String[]{"Selected images (" + patches.size() + ")", "All images in layer " + (ref.getLayer().getParent().indexOf(ref.getLayer()) + 1), "All images in layer range..."});

		final Component[] cs = new Component[]{set, pulldown, tcf, tp, taf};

		set.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent e) {
				if (check(frame, filters)) {
					final ArrayList<Patch> ps = new ArrayList<Patch>();
					switch (pulldown.getSelectedIndex()) {
					case 0:
						ps.addAll(patches);
						break;
					case 1:
						for (final Displayable d : ref.getLayer().getDisplayables(Patch.class)) {
							ps.add((Patch)d);
						}
						break;
					case 2:
						final GenericDialog gd = new GenericDialog("Apply filters");
						Utils.addLayerRangeChoices(ref.getLayer(), gd);
						gd.addStringField("Image title matches:", "", 30);
						gd.addCheckbox("Visible images only", true);
						gd.showDialog();
						if (gd.wasCanceled()) return;
						final String regex = gd.getNextString();
						final boolean visible_only = gd.getNextBoolean();
						Pattern pattern = null;
						if (0 != regex.length()) {
							pattern = Pattern.compile(regex);
						}
						for (final Layer l : ref.getLayer().getParent().getLayers(gd.getNextChoiceIndex(), gd.getNextChoiceIndex())) {
							for (final Displayable d : l.getDisplayables(Patch.class, visible_only)) {
								if (null != pattern && !pattern.matcher(d.getTitle()).matches()) {
									continue;
								}
								ps.add((Patch)d);
							}
						}
					}
					apply(ps, filters, cs, false);
				}
			}
		});
		final JPanel buttons = new JPanel();
		final JLabel label = new JLabel("Push F1 for help");
		final GridBagConstraints c2 = new GridBagConstraints();
		final GridBagLayout gb2 = new GridBagLayout();
		buttons.setLayout(gb2);

		c2.anchor = GridBagConstraints.WEST;
		c2.gridx = 0;
		gb2.setConstraints(label, c2);
		buttons.add(label);

		c2.gridx = 1;
		c2.weightx = 1;
		final JPanel empty = new JPanel();
		gb2.setConstraints(empty, c2);
		buttons.add(empty);

		c2.gridx = 2;
		c2.weightx = 0;
		c2.anchor = GridBagConstraints.EAST;
		final JLabel a = new JLabel("Apply to:");
		gb2.setConstraints(a, c2);
		buttons.add(a);

		c2.gridx = 3;
		c2.insets = new Insets(4, 10, 4, 0);
		gb2.setConstraints(pulldown, c2);
		buttons.add(pulldown);

		c2.gridx = 4;
		gb2.setConstraints(set, c2);
		buttons.add(set);

		//
		taf.setPreferredSize(new Dimension(350, 500));
		tcf.setPreferredSize(new Dimension(350, 250));
		tp.setPreferredSize(new Dimension(350, 250));
		//
		final GridBagLayout gb = new GridBagLayout();
		final GridBagConstraints c = new GridBagConstraints();

		final JPanel all = new JPanel();
		all.setBackground(Color.white);
		all.setPreferredSize(new Dimension(700, 500));
		all.setLayout(gb);

		c.gridx = 0;
		c.gridy = 0;
		c.anchor = GridBagConstraints.NORTHWEST;
		c.fill = GridBagConstraints.BOTH;
		c.gridheight = 2;
		c.weightx = 0.5;
		final JScrollPane p1 = new JScrollPane(taf);
		p1.setPreferredSize(taf.getPreferredSize());
		gb.setConstraints(p1, c);
		all.add(p1);

		c.gridx = 1;
		c.gridy = 0;
		c.gridheight = 1;
		c.weighty = 0.7;
		final JScrollPane p2 = new JScrollPane(tcf);
		p2.setPreferredSize(tcf.getPreferredSize());
		gb.setConstraints(p2, c);
		all.add(p2);

		c.gridx = 1;
		c.gridy = 1;
		c.weighty = 0.3;
		final JScrollPane p3 = new JScrollPane(tp);
		p3.setPreferredSize(tp.getPreferredSize());
		gb.setConstraints(p3, c);
		all.add(p3);

		c.gridx = 0;
		c.gridy = 2;
		c.gridwidth = 2;
		c.weightx = 1;
		c.weighty = 0;
		gb.setConstraints(buttons, c);
		all.add(buttons);

		final KeyAdapter help = new KeyAdapter() {
			@Override
			public void keyPressed(final KeyEvent ke) {
				if (ke.getKeyCode() == KeyEvent.VK_F1) {
					final GenericDialog gd = new GenericDialog("Help :: image filters");
					gd.addMessage(
						  "In the table 'Available Filters':\n"
						+ " - double-click a filter to add it to the table of 'Chosen Filters'\n \n"
						+ "In the table 'Chosen Filters':\n"
						+ " - double-click a filter to remove it.\n"
						+ " - PAGE UP/DOWN keys move the filter up/down in the list.\n"
						+ " - 'Delete' key removes the selected filter.\n \n"
						+ "In the table of parameter names and values:\n"
						+ " - double-click a value to edit it. Then push enter to set the new value.\n \n"
						+ "What you need to know:\n"
						+ " - filters are set and applied in order, so order matters.\n"
						+ " - filters with parameters for which you entered a value of zero\nwill result in a warning message.\n"
						+ " - when applying the filters, if you choose 'Selected images', these are the images\nthat were selected when the filter editor was opened.\n"
						+ " - when applying the filters, if you want to filter for a regular expression pattern\nin the image name, use the 'All images in layer range...' option,\nwhere a range of one single layer is also possible."
					);
					gd.hideCancelButton();
					gd.setModal(false);
					gd.showDialog();
				}
			}
		};
		taf.addKeyListener(help);
		tcf.addKeyListener(help);
		tp.addKeyListener(help);
		all.addKeyListener(help);
		buttons.addKeyListener(help);
		empty.addKeyListener(help);
		a.addKeyListener(help);

		frame.getContentPane().add(all);
		frame.pack();
		frame.setVisible(true);
	}

	private static boolean check(final JFrame frame, final List<FilterWrapper> wrappers) {
		if (!wrappers.isEmpty()) {
			final String s = sanityCheck(wrappers);
			return 0 == s.length()
					|| new YesNoCancelDialog(frame, "WARNING", s + "\nContinue?").yesPressed();
		}
		return true;
	}

	private static String sanityCheck(final List<FilterWrapper> wrappers) {
		// Check all variables, find any numeric ones whose value is zero
		// Check for duplicated filters
		final HashSet<Class<?>> unique = new HashSet<Class<?>>();
		for (final FilterWrapper w : wrappers) {
			unique.add(w.filter.getClass());
		}
		final StringBuilder sb = new StringBuilder();
		if (wrappers.size() != unique.size()) {
			sb.append("WARNING: there are repeated filters!\n");
		}
		for (final FilterWrapper w : wrappers) {
			for (final Field f : w.fields) {
				try {
					final String zero = f.get(w.filter).toString();
					if ("0".equals(zero) || "0.0".equals(zero)) {
						sb.append("WARNING: parameter '" + f.getName() + "' of filter '" + w.filter.getClass().getSimpleName() + "' is zero!\n");
					}
				} catch (final Exception e) {
					e.printStackTrace();
				}
			}
		}
		return sb.toString();
	}

	private static IFilter[] asFilters(final List<FilterWrapper> wrappers) {
		if (wrappers.isEmpty()) return null;
		final IFilter[] fs = new IFilter[wrappers.size()];
		int next = 0;
		for (final FilterWrapper fw : wrappers) {
			fs[next++] = new FilterWrapper(fw.filter).filter; // a copy
		}
		return fs;
	}

	private static void apply(final Collection<Patch> patches, final List<FilterWrapper> wrappers, final Component[] cs, final boolean append) {
		Bureaucrat.createAndStart(new Worker.Task("Set filters") {
			@Override
			public void exec() {
				try {
					for (final Component c : cs) c.setEnabled(false);
					// Undo step
					final LayerSet ls = patches.iterator().next().getLayerSet();
					ls.addDataEditStep(new HashSet<Displayable>(patches));
					//
					final ArrayList<Future<?>> fus = new ArrayList<Future<?>>();
					for (final Patch p : patches) {
						final IFilter[] fs = asFilters(wrappers); // each Patch gets a copy
						if (append) p.appendFilters(fs);
						else p.setFilters(fs);
						p.getProject().getLoader().decacheImagePlus(p.getId());
						fus.add(p.updateMipMaps());
					}
					Utils.wait(fus);
					// Current state
					ls.addDataEditStep(new HashSet<Displayable>(patches));
				} finally {
					for (final Component c : cs) c.setEnabled(true);
				}
			}
		}, patches.iterator().next().getProject());
	}

	static public final IFilter[] duplicate(final IFilter[] fs) {
		if (null == fs) return fs;
		final IFilter[] copy = new IFilter[fs.length];
		for (int i=0; i<fs.length; ++i) copy[i] = new FilterWrapper(fs[i]).filter;
		return copy;
	}
}
