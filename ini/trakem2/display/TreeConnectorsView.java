package ini.trakem2.display;

import java.awt.Dimension;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

/** List all connectors whose origins intersect with the given tree. */
public class TreeConnectorsView {

	private JFrame frame;
	private TargetsTableModel model;
	private Tree tree;
	private List<Row> rows;

	public TreeConnectorsView(final Tree tree) {
		this.tree = tree;
		update();
		createGUI();
	}

	static public Bureaucrat create(final Tree tree) {
		return Bureaucrat.createAndStart(new Worker.Task("Opening connectors table") {
			public void exec() {
				new TreeConnectorsView(tree);
			}
		}, tree.getProject());
	}

	private class Row {
		final Connector connector;
		final int i;
		final ArrayList<Displayable> targets;
		String targetids;
		Row(final Connector c, final int i, final Set<Displayable> targets) {
			this.connector = c;
			this.i = i;
			this.targets = new ArrayList<Displayable>(targets);
		}
		final Coordinate getCoordinate(int col) {
			switch (col) {
				case 0:
					return connector.getCoordinateAtOrigin();
				default:
					return connector.getCoordinate(i);
			}
		}
		final long getFirstTargetId() {
			switch (targets.size()) {
				case 0:
					return 0; // let the empty targets get sorted to the top
				default:
					return targets.iterator().next().getId();
			}
		}
		final long getColumn(final int col) {
			switch (col) {
				case 0:
					return connector.getId();
				default:
					return getFirstTargetId();
			}
		}
		final String getTargetIds() {
			if (null == targetids) {
				switch (targets.size()) {
					case 0:
						targetids = "";
						break;
					case 1:
						targetids = Long.toString(targets.get(0).getId());
						break;
					default:
						final StringBuilder sb = new StringBuilder();
						for (final Displayable d : targets) {
							sb.append(d).append(',').append(' ');
						}
						sb.setLength(sb.length() -2);
						targetids = sb.toString();
						break;
				}
			}
			return targetids;
		}
	}

	public void update() {
		// Find all Connector instances intersecting with the nodes of Tree
		final List<Row> rows = new ArrayList<Row>();
		final Set<Connector> cons_unique = new HashSet<Connector>();
		for (final Node nd : (Collection<Node>)this.tree.getRoot().getSubtreeNodes()) {
			final Area a = nd.getArea();
			a.transform(this.tree.at);
			final Collection<Connector> ds = (Collection<Connector>)(Collection)nd.la.getParent().find(Connector.class, nd.la, a, true);
			ds.removeAll(cons_unique);
			for (final Connector c : ds) {
				int i = 0;
				for (final Set<Displayable> targets : c.getTargets(ZDisplayable.class)) {
					rows.add(new Row(c, i++, targets));
				}
			}
		}
		this.rows = rows;
		// update table model
		if (null != model) {
			model.fireTableDataChanged();
			model.fireTableStructureChanged();
		}
	}

	private void createGUI() {
		this.frame = new JFrame("Connectors for Tree #" + this.tree.getId());
		this.model = new TargetsTableModel();
		JTable table = new Table();
		table.setModel(model);
		JScrollPane jsp = new JScrollPane(table);
		jsp.setPreferredSize(new Dimension(500, 500));
		frame.getContentPane().add(jsp);
		frame.pack();
		frame.setVisible(true);
	}

	private class Table extends JTable {
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
									Display.getFront().getSelection().add(rows.get(row).connector);
								} else if (src == update) {
									TreeConnectorsView.this.update();
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
			Display.centerAt(rows.get(row).getCoordinate(col));
		}
	}

	private class TargetsTableModel extends AbstractTableModel {

		public int getColumnCount() { return 2; }
		public String getColumnName(final int col) {
			switch (col) {
				case 0: return "Connector id";
				case 1: return "Target id";
				default: return null;
			}
		}
		public int getRowCount() { return rows.size(); }
		public Object getValueAt(final int row, final int col) {
			switch (col) {
				case 0: return rows.get(row).connector.getId();
				case 1: return rows.get(row).getTargetIds();
				default: return null;
			}
		}
		public boolean isCellEditable(int row, int col) { return false; }
		public void setValueAt(Object value, int row, int col) {}
		final void sortByColumn(final int col, final boolean descending) {
			final ArrayList<Row> rows = new ArrayList<Row>(TreeConnectorsView.this.rows);
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
			TreeConnectorsView.this.rows = rows; // swap
			fireTableDataChanged();
			fireTableStructureChanged();
		}
	}

}
