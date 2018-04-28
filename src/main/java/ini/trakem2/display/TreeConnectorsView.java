package ini.trakem2.display;

import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

/** List all connectors whose origins intersect with the given tree. */
public class TreeConnectorsView {

	static private Map<Tree<?>,TreeConnectorsView> open = Collections.synchronizedMap(new HashMap<Tree<?>,TreeConnectorsView>());
	
	private JFrame frame;
	private TargetsTableModel outgoing_model = new TargetsTableModel(),
				  incoming_model = new TargetsTableModel();
	private Tree<?> tree;

	public TreeConnectorsView(final Tree<?> tree) {
		this.tree = tree;
		update();
		createGUI();
		open.put(tree,this);
	}

	static public Bureaucrat create(final Tree<?> tree) {
		return Bureaucrat.createAndStart(new Worker.Task("Opening connectors table") {
			public void exec() {
				TreeConnectorsView tcv = open.get(tree);
				if (null != tcv) {
					tcv.update();
					tcv.frame.setVisible(true);
					tcv.frame.toFront();
				} else {
					// Create and store in the Map of 'open'
					new TreeConnectorsView(tree);
				}
			}
		}, tree.getProject());
	}

	static public void dispose(final Tree<?> tree) {
		TreeConnectorsView tcv = open.remove(tree);
		if (null == tcv) return;
		tcv.frame.dispose();
	}

	static private final Comparator<Displayable> IDSorter = new Comparator<Displayable>() {
		@Override
		public int compare(Displayable o1, Displayable o2) {
			if (o1.getId() < o1.getId()) return -1;
			return 1;
		}
	};
	
	private class Row {
		final Connector connector;
		final int i;
		final ArrayList<Displayable> origins, targets;
		String originids, targetids;
		Row(final Connector c, final int i, final ArrayList<Displayable> origins, final ArrayList<Displayable> targets) {
			this.connector = c;
			this.i = i;
			this.origins = origins;
			this.targets = targets;
			for (final Iterator<Displayable> it = this.targets.iterator(); it.hasNext(); ) {
				if (it.next().getClass() == Connector.class) {
					it.remove();
				}
			}
		}
		final Coordinate<Node<Float>> getCoordinate(int col) {
			switch (col) {
				case 0:
				case 1:
					return connector.getCoordinateAtOrigin();
				case 2:
					return connector.getCoordinate(i);
				default:
					Utils.log2("Can't deal with column " + col);
					return null;
			}
		}
		private final long getFirstId(final ArrayList<Displayable> c) {
			if (c.isEmpty())
					return 0;
			else
					return c.get(0).getId();
		}
		final long getColumn(final int col) {
			switch (col) {
				case 0:
					return connector.getId();
				case 1:
					return getFirstId(origins);
				case 2:
					return getFirstId(targets);
				default:
					Utils.log2("Don't know how to deal with column " + col);
					return 0;
			}
		}
		private final String getIds(String ids, final ArrayList<Displayable> ds) {
			if (null == ids) {
				switch (ds.size()) {
					case 0:
						ids = "";
						break;
					case 1:
						ids = ds.get(0).toString();
						break;
					default:
						final StringBuilder sb = new StringBuilder();
						for (final Displayable d : ds) {
							sb.append(d).append(',').append(' ');
						}
						sb.setLength(sb.length() -2);
						ids = sb.toString();
						break;
				}
			}
			return ids;
		}
		final String getTargetIds() {
			targetids = getIds(targetids, targets);
			return targetids;
		}
		final String getOriginIds() {
			originids = getIds(originids, origins);
			return originids;
		}
	}

	public void update() {
		// Find all Connector instances intersecting with the nodes of Tree
		try {
			final Collection<Connector>[] connectors = this.tree.findConnectors();
			outgoing_model.setData(connectors[0]);
			incoming_model.setData(connectors[1]);
		} catch (Exception e) {
			IJError.print(e);
		}
	}

	private void addTab(JTabbedPane tabs, String title, TargetsTableModel model) {
		JTable table = new Table();
		table.setModel(model);
		JScrollPane jsp = new JScrollPane(table);
		jsp.setPreferredSize(new Dimension(500, 500));
		tabs.addTab(title, jsp);
	}

	private void createGUI() {
		this.frame = new JFrame("Connectors for Tree #" + this.tree.getId());
		frame.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent we) {
				open.remove(tree);
			}
		});
		JTabbedPane tabs = new JTabbedPane();
		addTab(tabs, "Outgoing", outgoing_model);
		addTab(tabs, "Incoming", incoming_model);
		frame.getContentPane().add(tabs);
		frame.pack();
		frame.setVisible(true);
	}

	private class Table extends JTable {
		private static final long serialVersionUID = 1L;
		Table() {
			super();
			getTableHeader().addMouseListener(new MouseAdapter() {
				public void mouseClicked(MouseEvent me) {
					if (2 != me.getClickCount()) return;
					int viewColumn = getColumnModel().getColumnIndexAtX(me.getX());
					int column = convertColumnIndexToModel(viewColumn);
					if (-1 == column) return;
					((TargetsTableModel)getModel()).sortByColumn(column, me.isShiftDown());
				}
			});
			addMouseListener(new MouseAdapter() {
				public void mousePressed(MouseEvent me) {
					final int row = Table.this.rowAtPoint(me.getPoint());
					final int col = Table.this.columnAtPoint(me.getPoint());
					if (2 == me.getClickCount()) {
						go(col, row);
					} else if (Utils.isPopupTrigger(me)) {
						JPopupMenu popup = new JPopupMenu();
						final JMenuItem go = new JMenuItem("Go"); popup.add(go);
						final JMenuItem goandsel = new JMenuItem("Go and select"); popup.add(go);
						final JMenuItem update = new JMenuItem("Update"); popup.add(update);
						ActionListener listener = new ActionListener() {
							public void actionPerformed(ActionEvent ae) {
								final Object src = ae.getSource();
								if (src == go) go(col, row);
								else if (src == goandsel) {
									go(col, row);
									if (0 != (ae.getModifiers() ^ ActionEvent.SHIFT_MASK)) Display.getFront().getSelection().clear();
									TargetsTableModel ttm = (TargetsTableModel)getModel();
									Display.getFront().getSelection().add(ttm.rows.get(row).connector);
								} else if (src == update) {
									Bureaucrat.createAndStart(new Worker.Task("Updating...") {
										public void exec() {
											TreeConnectorsView.this.update();
										}
									}, TreeConnectorsView.this.tree.getProject());
								}
							}
						};
						go.addActionListener(listener);
						goandsel.addActionListener(listener);
						update.addActionListener(listener);
						popup.show(Table.this, me.getX(), me.getY());
					}
				}
			});
		}
		void go(int col, int row) {
			TargetsTableModel ttm = (TargetsTableModel)getModel();
			Display.centerAt(ttm.rows.get(row).getCoordinate(col));
		}
	}

	private class TargetsTableModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		List<Row> rows = null;

		synchronized public void setData(final Collection<Connector> connectors) {
			this.rows = new ArrayList<Row>(connectors.size());
			for (final Connector c : connectors) {
				int i = 0;
				final ArrayList<Displayable> origins = new ArrayList<Displayable>(c.getOrigins(VectorData.class, true));
				Collections.sort(origins, IDSorter);
				for (final Set<Displayable> targets : c.getTargets(VectorData.class, true)) {
					final ArrayList<Displayable> ts = new ArrayList<Displayable>(targets);
					Collections.sort(ts, IDSorter);
					this.rows.add(new Row(c, i++, origins, ts));
				}
			}
			SwingUtilities.invokeLater(new Runnable() {public void run() {
				fireTableDataChanged();
				fireTableStructureChanged();
			}});
		}

		public int getColumnCount() { return 3; }
		public String getColumnName(final int col) {
			switch (col) {
				case 0: return "Connector id";
				case 1: return "Origin id";
				case 2: return "Target id";
				default: return null;
			}
		}
		public int getRowCount() { return rows.size(); }
		public Object getValueAt(final int row, final int col) {
			switch (col) {
				case 0: return rows.get(row).connector.getId();
				case 1: return rows.get(row).getOriginIds();
				case 2: return rows.get(row).getTargetIds();
				default: return null;
			}
		}
		public boolean isCellEditable(int row, int col) { return false; }
		public void setValueAt(Object value, int row, int col) {}
		final void sortByColumn(final int col, final boolean descending) {
			final ArrayList<Row> rows = new ArrayList<Row>(this.rows);
			Collections.sort(rows, new Comparator<Row>() {
				public int compare(final Row r1, final Row r2) {
					final long op = r1.getColumn(col) - r2.getColumn(col);
					if (descending) {
						if (op > 0) return -1;
						if (op < 0) return 1;
						return 0;
					}
					if (op < 0) return -1;
					if (op > 0) return 1;
					return 0;
				}
			});
			this.rows = rows; // swap
			fireTableDataChanged();
			fireTableStructureChanged();
		}
	}

}
