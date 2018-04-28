package ini.trakem2.utils;

import ini.trakem2.Project;
import ini.trakem2.display.AreaTree;
import ini.trakem2.display.Connector;
import ini.trakem2.display.Display;
import ini.trakem2.display.Display3D;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Node;
import ini.trakem2.display.Tag;
import ini.trakem2.display.Tree;
import ini.trakem2.display.Treeline;
import ini.trakem2.display.ZDisplayable;
import ini.trakem2.parallel.TaskFactory;
import ini.trakem2.persistence.FSLoader;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

public class Merger {
	/** Take two projects and find out what is different among them,
	 *  independent of id. */
	static public final void compare(final Project p1, final Project p2) {
		Utils.log("Be warned: only Treeline, AreaTree and Connector are considered at the moment.");

		final LayerSet ls1 = p1.getRootLayerSet(),
					   ls2 = p2.getRootLayerSet();

		final Collection<ZDisplayable> zds1 = ls1.getZDisplayables(),
									   zds2 = ls2.getZDisplayables();

		final HashSet<Class<?>> accepted = new HashSet<Class<?>>();
		accepted.add(Treeline.class);
		accepted.add(AreaTree.class);
		accepted.add(Connector.class);

		final HashMap<Displayable,List<Change>> matched = new HashMap<Displayable,List<Change>>();
		
		final HashSet<ZDisplayable> empty1 = new HashSet<ZDisplayable>(),
									empty2 = new HashSet<ZDisplayable>();

		final HashSet<ZDisplayable> unmatched1 = new HashSet<ZDisplayable>(),
									unmatched2 = new HashSet<ZDisplayable>(zds2);

		// Remove instances of classes not accepted
		for (final Iterator<ZDisplayable> it = unmatched2.iterator(); it.hasNext(); ) {
			ZDisplayable zd = it.next();
			if (!accepted.contains(zd.getClass())) {
				it.remove();
				continue;
			}
			if (zd.isDeletable()) {
				it.remove();
				empty2.add(zd);
			}
		}
		zds2.removeAll(empty2);
		
		final AtomicInteger counter = new AtomicInteger(0);
		
		// For every Displayable in p1, find a corresponding Displayable in p2
		// or at least one or more that are similar in that they have some nodes in common.
		try {
			ini.trakem2.parallel.Process.unbound(zds1,
					new TaskFactory<ZDisplayable, Object>() {
						@Override
						public Object process(final ZDisplayable zd1) {
							Utils.showProgress(counter.getAndIncrement() / (float)zds1.size());
							if (!accepted.contains(zd1.getClass())) {
								Utils.log("Ignoring: [A] " + zd1);
								return null;
							}
							if (zd1.isDeletable()) {
								synchronized (empty1) {
									empty1.add(zd1);
								}
								return null;
							}
							final List<Change> cs = new ArrayList<Change>();
							for (final ZDisplayable zd2 : zds2) {
								// Same class?
								if (zd1.getClass() != zd2.getClass()) continue;
								if (zd1 instanceof Tree<?> && zd2 instanceof Tree<?>) {
									Change c = compareTrees(zd1, zd2);
									if (c.hasSimilarNodes()) {
										cs.add(c);
										if (1 == cs.size()) {
											synchronized (matched) {
												matched.put(zd1, cs);
											}
										}
										synchronized (unmatched2) {
											unmatched2.remove(zd2);
										}
									}
									// debug
									if (zd1.getId() == zd2.getId()) {
										Utils.log("zd1 #" + zd1.getId() + " is similar to #" + zd2.getId() + ": " + c.hasSimilarNodes());
									}
								}
							}
							if (cs.isEmpty()) {
								synchronized (unmatched1) {
									unmatched1.add(zd1);
								}
							}
							return null;
						}
					});
		} catch (Exception e) {
			IJError.print(e);
		}
		
		Utils.showProgress(1); // reset

		Utils.log("matched.size(): " + matched.size());
		
		makeGUI(p1, p2, empty1, empty2, matched, unmatched1, unmatched2);
	}


	private static class Change {
		ZDisplayable d1, d2;
		/** If the title is different. */
		boolean title = false;
		/** If the AffineTransform is different. */
		boolean transform = false;
		/** If the tree has been rerooted. */
		boolean root = false;
		/** The difference in the number of nodes, in percent */
		int diff = 0;
		/** Number of nodes in d1 found also in d2 (independent of tags). */
		int common_nodes = 0;
		/** Number of nodes present in d1 but not in d2. */
		int d1_only = 0;
		/** Number of nodes present in d2 but not in d1. */
		int d2_only = 0;

		/** Total number of nodes in d1. */
		int n_nodes_d1 = 0;
		/** Total number of nodes in d2. */
		int n_nodes_d2 = 0;
		
		/** Number of tags in common (node-wise, not overall tags) */
		int common_tags = 0;
		/** Number of tags present in a node of d1 but not in d2. */
		int tags_1_only = 0;
		/** Number of tags present in a node of d1 but not in d2. */
		int tags_2_only = 0;

		Change(ZDisplayable d1, ZDisplayable d2) {
			this.d1 = d1;
			this.d2 = d2;
		}

		/** WARNING: if both trees have zero nodes, returns true as well. */
		boolean identical() {
			return !title && !transform && !root && 0 == diff
			    && 0 == tags_1_only && 0 == tags_2_only
			    && 0 == d1_only && 0 == d2_only;
		}

		boolean hasSimilarNodes() {
			return common_nodes > 0;
		}
	}

	/** Just so that hashCode will be 0 always and HashMap will be forced to use equals instead,
	 *  and also using the affine. */
	static private final class WNode {
		final Node<?> nd;
		final float x, y;
		final double z;
		WNode(Node<?> nd, AffineTransform aff) {
			this.nd = nd;
			float[] f = new float[]{nd.getX(), nd.getY()};
			aff.transform(f, 0, f, 0, 1);
			this.x = f[0];
			this.y = f[1];
			this.z = nd.getLayer().getZ();
		}
		@Override
		public final int hashCode() {
			return 0;
		}
		/** Compares only the node's world coordinates. */
		@Override
		public final boolean equals(Object ob) {
			final WNode o = (WNode)ob;
			return same(x, o.x) && same(y, o.y) && same(z, o.z);
		}
		private final boolean same(final float f1, final float f2) {
			return Math.abs(f1 - f2) < 0.01;
		}
		private final boolean same(final double f1, final double f2) {
			return Math.abs(f1 - f2) < 0.01;
		}
	}

	/*
	static private final HashSet<WNode> asWNodes(final Collection<Node<?>> nds, final AffineTransform aff) {
		final HashSet<WNode> col = new HashSet<WNode>();
		for (final Node<?> nd : nds) {
			col.add(new WNode(nd, aff));
		}
		return col;
	}
	*/
	static private final HashSet<WNode> asWNodes(final Tree<?> tree) {
		final HashSet<WNode> col = new HashSet<WNode>();
		for (final Node<?> nd : tree.getRoot().getSubtreeNodes()) {
			col.add(new WNode(nd, tree.getAffineTransform()));
		}
		return col;
	}
	
	/** Returns three lists: the tags in common, the tags in this node but not in the other,
	 *  and the tags in the other but not in this node. */
	static public List<Set<Tag>> compareTags(final Node<?> nd1, final Node<?> nd2) {
		final Set<Tag> tags1 = nd1.getTags(),
					   tags2 = nd2.getTags();
		if (null == tags1 && null == tags2) {
			return null;
		}
		final HashSet<Tag> common = new HashSet<Tag>();
		if (null != tags1 && null != tags2) {
			common.addAll(tags1);
			common.retainAll(tags2);
		}
		final HashSet<Tag> only1 = new HashSet<Tag>();
		if (null != tags1) {
			only1.addAll(tags1);
			if (null != tags2) only1.removeAll(tags2);
		}
		final HashSet<Tag> only2 = new HashSet<Tag>();
		if (null != tags2) {
			only2.addAll(tags2);
			if (null != tags1) only2.removeAll(tags1);
		}
		final List<Set<Tag>> t = new ArrayList<Set<Tag>>();
		t.add(common);
		t.add(only1);
		t.add(only2);
		return t;
	}

	private static Change compareTrees(final ZDisplayable zd1, final ZDisplayable zd2) {
		final Tree<?> t1 = (Tree<?>)zd1,
					  t2 = (Tree<?>)zd2;
		Change c = new Change(zd1, zd2);
		// Title:
		if (!t1.getTitle().equals(t2.getTitle())) {
			c.title = true;
		}
		// Transform:
		if (!t2.getAffineTransform().equals(t2.getAffineTransform())) {
			c.transform = true;
		}
		// Data
		final HashSet<WNode> nds1 = asWNodes(t1);
		final HashMap<WNode,WNode> nds2 = new HashMap<WNode,WNode>();
		for (final Node<?> nd : t2.getRoot().getSubtreeNodes()) {
			WNode nn = new WNode(nd, t2.getAffineTransform());
			nds2.put(nn, nn);
		}

		// Which nodes are similar?
		final HashSet<WNode> diff = new HashSet<WNode>(nds1);
		diff.removeAll(nds2.keySet());

		c.common_nodes = nds1.size() - diff.size();
		c.n_nodes_d1 = nds1.size();
		c.n_nodes_d2 = nds2.size();
		c.d1_only = c.n_nodes_d1 - c.common_nodes;
		c.d2_only = c.n_nodes_d2 - c.common_nodes;

		// Same amount of nodes?
		c.diff = nds1.size() - nds2.size();

		if (t1.getId() == t2.getId()) {
			Utils.log2("nds1.size(): " + nds1.size() + ", nds2.size(): " + nds2.size() + ", diff.size(): " + diff.size()
					+ ", c.common_nodes: " + c.common_nodes + ", c.diff: " + c.diff);
		}

		// is the root the same? I.e. has it been re-rooted?
		if (nds1.size() > 0) {
			c.root = t1.getRoot().equals(t2.getRoot());
		}
		// what about tags?
		for (final WNode nd1 : nds1) {
			final WNode nd2 = nds2.get(nd1);
			if (null == nd2) continue;
			final List<Set<Tag>> t = compareTags(nd1.nd, nd2.nd);
			if (null == t) continue;
			c.common_tags += t.get(0).size();
			c.tags_1_only += t.get(1).size();
			c.tags_2_only += t.get(2).size();
		}

		return c;
	}

	private static class Row {
		static int COLUMNS = 17;
		Change c;
		boolean sent = false;
		Row(Change c) {
			this.c = c;
		}
		Comparable<?> getColumn(int i) {
			switch (i) {
			case 0:
				return c.d1.getClass().getSimpleName();
			case 1:
				return c.d1.getId();
			case 2:
				return c.d1.getProject().getMeaningfulTitle2(c.d1);
			case 3:
				return !c.title;
			case 4:
				return !c.transform;
			case 5:
				return !c.root;
			case 6:
				return c.common_nodes;
			case 7:
				return c.d1_only;
			case 8:
				return c.d2_only;
			case 9:
				return c.diff;
			case 10:
				return c.common_tags;
			case 11:
				return c.tags_1_only;
			case 12:
				return c.tags_2_only;
			case 13:
				return c.d2.getId();
			case 14:
				return c.d2.getProject().getMeaningfulTitle2(c.d2);
			case 15:
				return c.identical();
			case 16:
				return sent;
			default:
				Utils.log("Row.getColumn: Don't know what to do with column " + i);
				return null;
			}
		}
		Color getColor() {
			if (c.identical()) return Color.white;
			if (c.hasSimilarNodes()) {
				if (c.d1_only > 0 && c.d2_only > 0) {
					// Mixed changes: problem
					return Color.magenta;
				} else if (c.d1_only > 0) {
					// Changes only in d1
					return Color.orange;
				} else if (c.d2_only > 0) {
					// Changes only in d2
					return Color.pink;
				}
				// Same for tags
				if (c.tags_1_only > 0 && c.tags_2_only > 0) {
					return Color.magenta;
				} else if (c.tags_1_only > 0) {
					return Color.orange;
				} else if (c.tags_2_only > 0) {
					return Color.pink;
				}
			}
			return Color.red.brighter();
		}
		public void sent() {
			sent = true;
		}
		static private String getColumnName(int col) {
			switch (col) {
			case 0: return "Type";
			case 1: return "id 1";
			case 2: return "title 1";
			case 3: return "=title?";
			case 4: return "=affine?";
			case 5: return "=root?";
			case 6: return "Nodes common";
			case 7: return "N 1 only";
			case 8: return "N 2 only";
			case 9: return "N diff";
			case 10: return "Tags common";
			case 11: return "Tags 1 only tags";
			case 12: return "Tags 2 only tags";
			case 13: return "id 2";
			case 14: return "title 2";
			case 15: return "Identical?";
			case 16: return "sent";
			default:
				Utils.log("Row.getColumnName: Don't know what to do with column " + col);
				return null;
			}
		}
		static private int getSentColumn() {
			return Row.COLUMNS -1;
		}
	}

	private static void makeGUI(final Project p1, final Project p2,
			HashSet<ZDisplayable> empty1, HashSet<ZDisplayable> empty2,
			HashMap<Displayable,List<Change>> matched,
			HashSet<ZDisplayable> unmatched1,
			HashSet<ZDisplayable> unmatched2) {

		final ArrayList<Row> rows = new ArrayList<Row>();
		for (Map.Entry<Displayable,List<Change>> e : matched.entrySet()) {
			for (Change c : e.getValue()) {
				rows.add(new Row(c));
			}
			if (e.getValue().size() > 1) {
				Utils.log("More than one assigned to " + e.getKey());
			}
		}
		JTabbedPane tabs = new JTabbedPane();

		final Table table = new Table();
		tabs.addTab("Matched", new JScrollPane(table));

		JTable tu1 = createTable(unmatched1, "Unmatched 1", p1, p2);
		JTable tu2 = createTable(unmatched2, "Unmatched 2", p1, p2);
		JTable tu3 = createTable(empty1, "Empty 1", p1, p2);
		JTable tu4 = createTable(empty2, "Empty 2", p1, p2);

		tabs.addTab("Unmatched 1", new JScrollPane(tu1));
		tabs.addTab("Unmatched 2", new JScrollPane(tu2));
		tabs.addTab("Empty 1", new JScrollPane(tu3));
		tabs.addTab("Empty 2", new JScrollPane(tu4));

		for (int i=0; i<tabs.getTabCount(); i++) {
			if (null == tabs.getTabComponentAt(i)) {
				Utils.log2("null at " + i);
				continue;
			}
			tabs.getTabComponentAt(i).setPreferredSize(new Dimension(1024, 768));
		}

		String xml1 = new File(((FSLoader)p1.getLoader()).getProjectXMLPath()).getName();
		String xml2 = new File(((FSLoader)p2.getLoader()).getProjectXMLPath()).getName();
		JFrame frame = new JFrame("1: " + xml1 + "  ||  2: " + xml2);
		tabs.setPreferredSize(new Dimension(1024, 768));
		frame.getContentPane().add(tabs);
		frame.pack();
		frame.setVisible(true);

		// so the bullshit starts: any other way to set the model fails, because it tries to render it while setting it
		SwingUtilities.invokeLater(new Runnable() { public void run() {
			table.setModel(new Model(rows));
			CustomCellRenderer cc = new CustomCellRenderer();
			for (int i=0; i<Row.COLUMNS; i++) {
				table.setDefaultRenderer(table.getColumnClass(i), cc);
			}
		}});
	}

	static private class TwoColumnModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		final List<ZDisplayable> items = new ArrayList<ZDisplayable>();
		final boolean[] sent;
		TwoColumnModel(final HashSet<ZDisplayable> ds, final String title) {
			items.addAll(ds);
			sent = new boolean[items.size()];
		}
		@Override
		public boolean isCellEditable(int row, int col) {
			return false;
		}
		@Override
		public void setValueAt(Object value, int row, int col) {}
		@Override
		public int getColumnCount() {
			return 2;
		}
		@Override
		public int getRowCount() {
			return items.size();
		}
		@Override
		public Object getValueAt(int row, int col) {
			switch (col) {
			case 0:
				return items.get(row);
			case 1:
				return sent[row];
			default:
				return null;
			}
		}
		@Override
		public String getColumnName(int col) {
			switch (col) {
			case 0:
				return "unmatched";
			case 1:
				return "sent";
			default:
				return null;
			}
		}
	}

	static private JTable createTable(final HashSet<ZDisplayable> hs, final String column_title,
			final Project p1, final Project p2) {
		final TwoColumnModel tcm = new TwoColumnModel(hs, column_title);
		final JTable table = new JTable(tcm);
		table.setDefaultRenderer(table.getColumnClass(0), new DefaultTableCellRenderer() {
			private static final long serialVersionUID = 1L;
			@Override
			public Component getTableCellRendererComponent(JTable table, Object value,
					boolean isSelected, boolean hasFocus, int row, int column) {
				final Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
				if (1 == column && tcm.sent[row]) {
					c.setBackground(Color.green);
					c.setForeground(Color.white);
				} else if (isSelected) {
					c.setForeground(table.getSelectionForeground());
					c.setBackground(table.getSelectionBackground());
				} else {
					c.setBackground(Color.white);
					c.setForeground(Color.black);
				}
				return c;
			}
		});
		
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent me) {
				final JTable src = (JTable)me.getSource();
				final TwoColumnModel model = (TwoColumnModel)src.getModel();
				final int row = src.rowAtPoint(me.getPoint()),
						  col = src.columnAtPoint(me.getPoint());
				if (2 == me.getClickCount()) {
					Object ob = model.getValueAt(row, col);
					if (ob instanceof ZDisplayable) {
						ZDisplayable zd = (ZDisplayable)ob;
						Display df = Display.getOrCreateFront(zd.getProject());
						df.show(zd.getFirstLayer(), zd, true, false); // also select
					}
				} else if (me.isPopupTrigger()) {
					JPopupMenu popup = new JPopupMenu();
					final JMenuItem send = new JMenuItem("Send selection"); popup.add(send);
					send.addActionListener(new ActionListener() {
						@Override
						public void actionPerformed(ActionEvent ae) {
							ArrayList<ZDisplayable> col = new ArrayList<ZDisplayable>();
							for (final int i : src.getSelectedRows()) {
								col.add((ZDisplayable)model.getValueAt(i, 0));
							}
							if (col.isEmpty()) return;
							Project target = col.get(0).getProject() == p1 ? p2 : p1; // the other
							LayerSet ls = target.getRootLayerSet();
							ArrayList<ZDisplayable> copies = new ArrayList<ZDisplayable>();
							for (ZDisplayable zd : col) {
								copies.add((ZDisplayable) zd.clone(target, false));
								model.sent[row] = true;
							}
							// 1. To the LayerSet:
							ls.addAll(copies);
							// 2. To the ProjectTree:
							target.getProjectTree().insertSegmentations(copies);

							// Update:
							model.fireTableDataChanged();
						}
					});
					popup.show(table, me.getX(), me.getY());
				}
			}
		});
		
		return table;
	}

	static private final class Model extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		ArrayList<Row> rows;
		Model(ArrayList<Row> rows) {
			this.rows = rows;
		}
		public void sortByColumn(final int column, final boolean descending) {
			final ArrayList<Row> rows = new ArrayList<Row>(this.rows);
			Collections.sort(rows, new Comparator<Row>() {
				@SuppressWarnings("unchecked")
				public final int compare(Row o1, Row o2) {
					if (descending) {
						Row tmp = o1;
						o1 = o2;
						o2 = tmp;
					}
					Comparable<?> val1 = getValueAt(rows.indexOf(o1), column);
					Comparable<?> val2 = getValueAt(rows.indexOf(o2), column);
					return ((Comparable)val1).compareTo((Comparable)val2);
				}
			});
			this.rows = rows; // swap
			fireTableDataChanged();
			fireTableStructureChanged();
		}
		@Override
		public Comparable<?> getValueAt(int row, int col) {
			return rows.get(row).getColumn(col);
		}
		@Override
		public int getRowCount() {
			if (null == rows) return 0;
			return rows.size();
		}
		@Override
		public int getColumnCount() {
			return Row.COLUMNS;
		}
		@Override
		public boolean isCellEditable(int row, int col) {
			return false;
		}
		@Override
		public void setValueAt(Object value, int row, int col) {}
		@Override
		public String getColumnName(int col) {
			return Row.getColumnName(col);
		}
	}
	
	static private final class CustomCellRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
			final Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			final Row r = ((Model)table.getModel()).rows.get(row);
			if (Row.getSentColumn() == column && r.sent) {
				c.setForeground(Color.white);
				c.setBackground(Color.green);
				return c;
			}
			if (isSelected) {
				setForeground(table.getSelectionForeground());
				setBackground(table.getSelectionBackground());
			} else {
				c.setForeground(Color.black);
				c.setBackground(r.getColor());
			}
			return c;
		}
	}

	static private final class Table extends JTable {
		private static final long serialVersionUID = 1L;
		Table() {
			super();
			setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
			getTableHeader().addMouseListener(new MouseAdapter() {
				public void mouseClicked(MouseEvent me) { // mousePressed would fail to repaint due to asynch issues
					if (2 != me.getClickCount()) return;
					int viewColumn = getColumnModel().getColumnIndexAtX(me.getX());
					int column = convertColumnIndexToModel(viewColumn);
					if (-1 == column) return;
					((Model)getModel()).sortByColumn(column, me.isShiftDown());
				}
			});
			this.addMouseListener(new MouseAdapter() {
				public void mousePressed(MouseEvent me) {
					final int row = Table.this.rowAtPoint(me.getPoint());
					//final int col = Table.this.columnAtPoint(me.getPoint());
					final Row r = ((Model)getModel()).rows.get(row);
					if (Utils.isPopupTrigger(me)) {
						JPopupMenu popup = new JPopupMenu();
						final JMenuItem replace12 = new JMenuItem("Replace 1 with 2"); popup.add(replace12);
						final JMenuItem replace21 = new JMenuItem("Replace 2 with 1"); popup.add(replace21);
						popup.addSeparator();
						final JMenuItem sibling12 = new JMenuItem("Add 1 as sibling of 2"); popup.add(sibling12);
						final JMenuItem sibling21 = new JMenuItem("Add 2 as sibling of 1"); popup.add(sibling21);
						popup.addSeparator();
						final JMenuItem select = new JMenuItem("Select each in its own display"); popup.add(select);
						final JMenuItem select2 = new JMenuItem("Select and center each in its own display"); popup.add(select2);
						final JMenuItem show3D = new JMenuItem("Show both in 3D"); popup.add(show3D);
						ActionListener listener = new ActionListener() {
							@Override
							public void actionPerformed(ActionEvent e) {
								if (select == e.getSource()) {
									select(r.c.d1);
									select(r.c.d2);
								} else if (select2 == e.getSource()) {
									select2(r.c.d1);
									select2(r.c.d2);
								} else if (show3D == e.getSource()) {
									show3D(r.c.d1);
									show3D(r.c.d2);
								} else if (replace12 == e.getSource()) {
									// Replace 1 (old) with 2 (new_)
									if (replace(r.c.d1, r.c.d2)) {
										r.sent();
									}
								} else if (replace21 == e.getSource()) {
									// Replace 2 (old) with 1 (new_)
									if (replace(r.c.d2, r.c.d1)) {
										r.sent();
									}
								} else if (sibling12 == e.getSource()) {
									// add 1 (new_) as sibling of 2 (old)
									if (addAsSibling(r.c.d2, r.c.d1)) {
										r.sent();
									}
								} else if (sibling21 == e.getSource()) {
									// add 2 (new_) as sibling of 1 (old)
									if (addAsSibling(r.c.d1, r.c.d2)) {
										r.sent();
									}
								}
							}
						};
						for (final JMenuItem item : new JMenuItem[]{select, select2, show3D, replace12, replace21, sibling12, sibling21}) {
							item.addActionListener(listener);
						}
						popup.show(Table.this, me.getX(), me.getY());
					}
				}
			});
		}
		private void select(ZDisplayable d) {
			Display display = Display.getFront(d.getProject());
			if (null == display) {
				Utils.log("No displays open for project " + d.getProject());
			} else {
				display.select(d);
			}
		}
		private void select2(ZDisplayable d) {
			Display display = Display.getFront(d.getProject());
			if (null == display) {
				Utils.log("No displays open for project " + d.getProject());
			} else {
				Display.showCentered(d.getFirstLayer(), d, true, false); // also select
			}
		}
		private void show3D(Displayable d) {
			Display3D.show(d.getProject().findProjectThing(d));
		}
		/** Replace old with new in old's project. */
		private boolean replace(ZDisplayable old, ZDisplayable new_) {
			String xml_old = new File(((FSLoader)old.getProject().getLoader()).getProjectXMLPath()).getName();
			String xml_new = new File(((FSLoader)new_.getProject().getLoader()).getProjectXMLPath()).getName();
			if (!Utils.check("Really replace " + old + " (" + xml_old + ")\n" +
					    	 "with " + new_ + " (" + xml_new + ") ?")) {
				return false;
			}
			LayerSet ls = old.getLayerSet();
			ls.addChangeTreesStep();
			//
			addCopyAsSibling(old, new_);
			old.getProject().remove(old);
			//
			ls.addChangeTreesStep();
			Utils.log("Replaced " + old + " (from " + xml_old + ")\n" +
					  "    with " + new_ + " (from " + xml_new + ")");
			update();
			return true;
		}
		/** Add @param to_copy as a sibling of @param old. */
		private boolean addAsSibling(ZDisplayable old, ZDisplayable to_copy) {
			LayerSet ls = old.getLayerSet();
			ls.addChangeTreesStep();
			addCopyAsSibling(old, to_copy);
			ls.addChangeTreesStep();
			update();
			return true;
		}
		private void addCopyAsSibling(ZDisplayable old, ZDisplayable to_copy) {
			// Clone the sibling into the old's project
			ZDisplayable copy = (ZDisplayable) to_copy.clone(old.getProject(), false);
			old.getLayerSet().add(copy);
			old.getProject().getProjectTree().addSibling(old, copy);
		}
		private void update() {
			((Model)getModel()).fireTableDataChanged();
			((Model)getModel()).fireTableStructureChanged();
		}
	}

}