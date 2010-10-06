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

package ini.trakem2.utils;

import ij.gui.GenericDialog;
import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.display.AreaList;
import ini.trakem2.display.AreaTree;
import ini.trakem2.display.Ball;
import ini.trakem2.display.Connector;
import ini.trakem2.display.Coordinate;
import ini.trakem2.display.DLabel;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Dissector;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Node;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Pipe;
import ini.trakem2.display.Polyline;
import ini.trakem2.display.Profile;
import ini.trakem2.display.Tag;
import ini.trakem2.display.Tree;
import ini.trakem2.display.Treeline;
import ini.trakem2.display.ZDisplayable;
import ini.trakem2.persistence.DBObject;

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.regex.Pattern;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableModel;

public class Search {
	private JFrame search_frame = null;
	private JTabbedPane search_tabs = null;
	private JTextField search_field = null;
	private JComboBox pulldown = null;
	private KeyListener kl = null;

	static private Search instance = null;

	private Class<?>[] types = null;


	/** Creates the GUI for searching text in any TrakEM2 element. */
	public Search() {
		if (null != instance) {
			instance.makeGUI();
		} else {
			instance = this;
			types =  new Class[]{DBObject.class, Displayable.class, DLabel.class, Patch.class, AreaList.class, Profile.class, Pipe.class, Ball.class, Layer.class, Dissector.class, Polyline.class, Treeline.class, AreaTree.class, Connector.class};
			makeGUI();
		}
	}

	private void tryCloseTab(KeyEvent ke) {
		switch (ke.getKeyCode()) {
			case KeyEvent.VK_W:
				if (!ke.isControlDown()) return;
				int ntabs = search_tabs.getTabCount();
				if (0 == ntabs) {
					instance.destroy();
					return;
				}
				search_tabs.remove(search_tabs.getSelectedIndex());
				return;
			default:
				return;
		}
	}

	private void makeGUI() {
		// create GUI if not there
		if (null == search_frame) {
			search_frame = ControlWindow.createJFrame("Search Regular Expressions");
			search_frame.addWindowListener(new WindowAdapter() {
				public void windowClosing(WindowEvent we) {
					instance.destroy();
				}
			});
			search_tabs = new JTabbedPane(JTabbedPane.TOP);
			kl = new KeyAdapter() {
				public void keyPressed(KeyEvent ke) {
					tryCloseTab(ke);
				}
			};
			search_tabs.addKeyListener(kl);
			search_field = new JTextField(14);
			search_field.addKeyListener(new VKEnterListener());
			
			GridBagLayout gb = new GridBagLayout();
			GridBagConstraints c = new GridBagConstraints();
			JPanel all = new JPanel();
			all.setLayout(gb);
			all.setPreferredSize(new Dimension(400, 400));
			
			JButton b = new JButton("Search");
			b.addActionListener(new ButtonListener());
			pulldown = new JComboBox(new String[]{"All", "All displayables", "Labels", "Images", "Area Lists", "Profiles", "Pipes", "Balls", "Layers", "Dissectors", "Polylines", "Treelines", "AreaTrees", "Connectors"});

			c.gridx = 0;
			c.gridy = 0;
			c.fill = GridBagConstraints.HORIZONTAL;
			c.weightx = 1;
			c.insets = new Insets(4, 10, 5, 2);
			gb.setConstraints(search_field, c);
			all.add(search_field);
			
			c.gridx = 1;
			c.weightx = 0;
			c.insets = new Insets(4, 2, 5, 10);
			gb.setConstraints(b, c);
			all.add(b);
			
			c.gridx = 2;
			gb.setConstraints(pulldown, c);
			all.add(pulldown);
			
			c.gridx = 0;
			c.gridy = 1;
			c.gridwidth = 3;
			c.weightx = 1;
			c.weighty = 1;
			c.fill = GridBagConstraints.BOTH;
			c.insets = new Insets(0, 0, 0, 0);
			gb.setConstraints(search_tabs, c);
			all.add(search_tabs);

			search_frame.getContentPane().add(all);
			search_frame.pack();
			javax.swing.SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					ij.gui.GUI.center(search_frame);
					search_frame.setVisible(true);
				}
			});
		} else {
			search_frame.toFront();
		}
	}

	synchronized private void destroy() {
		if (null != instance) {
			if (null != search_frame) search_frame.dispose();
			search_frame = null;
			search_tabs = null;
			search_field = null;
			pulldown = null;
			types = null;
			kl = null;
			// deregister
			instance = null;
		}
	}

	private class DisplayableTableModel extends AbstractTableModel {
		private Vector v_obs;
		private Vector v_txt;
		private Vector v_co;
		DisplayableTableModel(Vector v_obs, Vector v_txt, Vector v_co) {
			super();
			this.v_obs = v_obs;
			this.v_txt = v_txt;
			this.v_co = v_co;
		}
		public String getColumnName(int col) {
			if (0 == col) return "Type";
			else if (1 == col) return "Info";
			else if (2 == col) return "Matched";
			else return "";
		}
		public int getRowCount() { return v_obs.size(); }
		public int getColumnCount() { return 3; }
		public Object getValueAt(int row, int col) {
			if (0 == col) return Project.getName(v_obs.get(row).getClass());
			else if (1 == col) return ((DBObject)v_obs.get(row)).getShortTitle();
			else if (2 == col) return v_txt.get(row).toString();
			else return "";
		}
		public DBObject getDBObjectAt(int row) {
			return (DBObject)v_obs.get(row);
		}
		public Displayable getDisplayableAt(int row) {
			return (Displayable)v_obs.get(row);
		}
		public Coordinate getCoordinateAt(int row) {
			return (Coordinate) v_co.get(row);
		}
		public boolean isCellEditable(int row, int col) {
			return false;
		}
		public void setValueAt(Object value, int row, int col) {
			// nothing
			//fireTableCellUpdated(row, col);
		}
		public boolean remove(Displayable displ) {
			int i = v_obs.indexOf(displ);
			if (-1 != i) {
				v_obs.remove(i);
				v_txt.remove(i);
				v_co.remove(i);
				return true;
			}
			return false;
		}
		public boolean contains(Object ob) {
			return v_obs.contains(ob);
		}
	}

	private void executeSearch() {
		final Project project;
		final Display display = Display.getFront();
		if (null == display) {
			project = ControlWindow.getActive();
		} else {
			project = display.getProject();
		}
		if (null == project) {
			// Should not happen
			return;
		}
		Bureaucrat.createAndStart(new Worker.Task("Searching") {
			public void exec() {

		String pattern = search_field.getText();
		if (null == pattern || 0 == pattern.length()) {
			return;
		}
		// fix pattern
		final String typed_pattern = pattern;
		final StringBuilder sb = new StringBuilder(); // I hate java
		if (!pattern.startsWith("^")) sb.append("^.*");
		for (int i=0; i<pattern.length(); i++) {
			sb.append(pattern.charAt(i));
		}
		if (!pattern.endsWith("$")) sb.append(".*$");
		pattern = sb.toString();
		final Pattern pat = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);
		//Utils.log2("pattern after: " + pattern);
		final ArrayList al = new ArrayList();
		//Utils.log("types[pulldown] = " + types[pulldown.getSelectedIndex()]);
		find(project.getRootLayerSet(), al, types[pulldown.getSelectedIndex()]);
		//Utils.log2("found labels: " + al.size());
		if (0 == al.size()) return;
		final Vector v_obs = new Vector();
		final Vector v_txt = new Vector();
		final Vector v_co = new Vector();
		Coordinate co = null;
		for (final Iterator it = al.iterator(); it.hasNext(); ) {
			if (Thread.currentThread().isInterrupted()) {
				return;
			}
			final DBObject dbo = (DBObject)it.next();
			boolean matched = false;
			// Search in its title
			Displayable d = null;
			if (dbo instanceof Displayable) {
				d = (Displayable)dbo;
			}
			String txt;
			String meaningful_title = null;
			if (null == d || Patch.class == d.getClass()) txt = dbo.getTitle();
			else {
				txt = meaningful_title = dbo.getProject().getMeaningfulTitle(d);
			}

			if (null == txt || 0 == txt.trim().length()) continue;
			matched = pat.matcher(txt).matches();
			if (!matched && null != d) {
				// Search also in its annotation
				txt = d.getAnnotation();
				if (null != txt) matched = pat.matcher(txt).matches();
			}
			if (!matched) {
				// Search also in its toString()
				txt = dbo.toString();
				matched = pat.matcher(txt).matches();
			}
			if (!matched) {
				// Search also in its id
				txt = Long.toString(dbo.getId());
				matched = pat.matcher(txt).matches();
				if (matched) txt = "id: #" + txt;
			}
			if (!matched && null != d) {
				// Search also in its properties
				Map<String,String> props = d.getProperties();
				if (null != props) {
					for (final Map.Entry<String,String> e : props.entrySet()) {
						if (pat.matcher(e.getKey()).matches()
						 || pat.matcher(e.getValue()).matches()) {
							matched = true;
							txt = e.getKey() + " => " + e.getValue() + " [property]";
							break;
						}
					}
				}
				if (!matched) {
					Map<Displayable,Map<String,String>> linked_props = ((Displayable)dbo).getLinkedProperties();
					if (null != linked_props) {
						for (final Map.Entry<Displayable,Map<String,String>> e : linked_props.entrySet()) {
							for (final Map.Entry<String,String> ee : e.getValue().entrySet()) {
								if (pat.matcher(ee.getKey()).matches()
								 || pat.matcher(ee.getValue()).matches()) {
									matched = true;
									txt = ee.getKey() + " => " + e.getValue() + " [linked property]";
									break;
								}
							}
						}
					}
				}
			}
			if (!matched && dbo instanceof Tree) {
				// search Node tags
				Node root = ((Tree)dbo).getRoot();
				if (null == root) continue;
				for (final Node nd : (Collection<Node>) root.getSubtreeNodes()) {
					Set<Tag> tags = nd.getTags();
					if (null == tags) continue;
					for (final Tag tag : tags) {
						if (pat.matcher(tag.toString()).matches()) {
							v_obs.add(dbo);
							v_txt.add(new StringBuilder(tag.toString()).append(" (").append(null == meaningful_title ? dbo.toString() : meaningful_title).append(')').toString());
							v_co.add(createCoordinate((Tree)dbo, nd));
						}
					}
				}
				continue; // all added if any
			}

			if (!matched) continue;

			//txt = txt.length() > 30 ? txt.substring(0, 27) + "..." : txt;
			v_obs.add(dbo);
			v_txt.add(txt);
			v_co.add(co);
		}

		if (0 == v_obs.size()) {
			Utils.showMessage("Nothing found.");
			return;
		}
		final JScrollPane jsp = makeTable(new DisplayableTableModel(v_obs, v_txt, v_co));
		search_tabs.addTab(typed_pattern, jsp);
		search_tabs.setSelectedComponent(jsp);
		//search_frame.pack();

		}}, project);
	}

	private Coordinate<Node> createCoordinate(Tree tree, Node nd) {
		double x = nd.getX(),
		       y = nd.getY();
		if (!tree.getAffineTransform().isIdentity()) {
			double[] dp = new double[]{x, y};
			tree.getAffineTransform().transform(dp, 0, dp, 0, 1);
			x = dp[0];
			y = dp[1];
		}
		return new Coordinate<Node>(x, y, nd.getLayer(), nd);
	}

	private Coordinate<Displayable> createCoordinate(final Displayable d) {
		Rectangle r = d.getBoundingBox();
		Layer la = d instanceof ZDisplayable ? ((ZDisplayable)d).getFirstLayer() : d.getLayer();
		if (null == la) {
			Display display = Display.getFront(d.getProject());
			if (null == display) la = d.getProject().getRootLayerSet().getLayer(0);
			else la = display.getLayer();
		}
		return new Coordinate<Displayable>(r.x+r.width/2, r.y+r.height/2, la, d);
	}

	private JScrollPane makeTable(TableModel model) {
		JTable table = new JTable(model);
		//java 1.6.0 only!! //table.setAutoCreateRowSorter(true);
		table.addMouseListener(new DisplayableListListener());
		table.addKeyListener(kl);
		JScrollPane jsp = new JScrollPane(table);
		jsp.setPreferredSize(new Dimension(500, 500));
		return jsp;
	}

	/** Listen to the search button. */
	private class ButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent ae) {
			executeSearch();
		}
	}
	/** Listen to the search field. */
	private class VKEnterListener extends KeyAdapter {
		public void keyPressed(KeyEvent ke) {
			if (ke.getKeyCode() == KeyEvent.VK_ENTER) {
				executeSearch();
				return;
			}
			tryCloseTab(ke);
		}
	}

	/** Listen to double clicks in the table rows. */
	private class DisplayableListListener extends MouseAdapter {
		public void mousePressed(MouseEvent me) {
			final JTable table = (JTable)me.getSource();
			final int row = table.rowAtPoint(me.getPoint());
			final DBObject ob = ((DisplayableTableModel)table.getModel()).getDBObjectAt(row);
			final Coordinate co = ((DisplayableTableModel)table.getModel()).getCoordinateAt(row);
			if (2 == me.getClickCount()) {
				if (null != co) {
					Display.centerAt(co);
				} else if (ob instanceof Displayable) {
					// no zoom
					Display.centerAt(createCoordinate((Displayable)ob), true, me.isShiftDown());
				} else if (ob instanceof Layer) {
					Display.showFront((Layer)ob);
				} else {
					Utils.log2("Search: Unhandable table selection: " + ob);
				}
			} else if (Utils.isPopupTrigger(me)) {
				JPopupMenu popup = new JPopupMenu();
				final String show2D = "Show";
				final String select = "Select in display";
				ActionListener listener = new ActionListener() {
					public void actionPerformed(ActionEvent ae) {
						final String command = ae.getActionCommand();
						if (show2D == command) {
							if (ob instanceof Displayable) {
								Display.centerAt(createCoordinate((Displayable)ob), true, 0 != (ae.getModifiers() & ActionEvent.SHIFT_MASK));
							} else if (ob instanceof Layer) {
								Display.showFront((Layer)ob);
							}
						} else if (command.equals(select)) {
							if (ob instanceof Layer) {
								Display.showFront((Layer)ob);
							} else if (ob instanceof Displayable) {
								// How many rows are selected?
								if (1 == table.getSelectedRowCount()) {
									Displayable displ = (Displayable)ob;
									if (!displ.isVisible()) displ.setVisible(true);
									Display display = Display.getFront(displ.getProject());
									if (null == display) return;
									boolean shift_down = 0 != (ae.getModifiers() & ActionEvent.SHIFT_MASK);
									display.select(displ, shift_down);
								} else {
									Collection<Displayable> ds = new ArrayList<Displayable>();
									Display display = null;
									HashSet<Layer> layers = new HashSet<Layer>();
									for (int row : table.getSelectedRows()) {
										final DBObject dob = ((DisplayableTableModel)table.getModel()).getDBObjectAt(row);
										if (null == dob || !(dob instanceof Displayable)) {
											Utils.log("Not selecting row " + row);
										} else {
											Displayable d = (Displayable)dob;
											ds.add(d);
											if (!(d instanceof ZDisplayable)) {
												layers.add(d.getLayer());
											}
											if (null == display) display = Display.getFront(dob.getProject());
										}
									}
									// Filter out Displayable not in the front layer
									if (layers.size() > 0) {
										GenericDialog gd = new GenericDialog("All layers?");
										String[] s = new String[]{"Only from current layer", "From " + layers.size() + " layers"};
										gd.addChoice("Select objects from:", s, s[0]);
										gd.showDialog();
										if (gd.wasCanceled()) return;
										if (0 == gd.getNextChoiceIndex()) {
											Layer la = display.getLayer();
											for (final Iterator<Displayable> it = ds.iterator(); it.hasNext(); ) {
												if (it.next().getLayer() != la) it.remove();
											}
										}
									}
									display.getSelection().selectAll(ds);
								}
							}
						}
					}
				};
				JMenuItem item = new JMenuItem(show2D); popup.add(item); item.addActionListener(listener);
				item = new JMenuItem(select); popup.add(item); item.addActionListener(listener);
				popup.show(table, me.getX(), me.getY());
			}
		}
	}

	/** Recursive search into nested LayerSet instances, accumulating instances of type into the list al. */
	private void find(final LayerSet set, final ArrayList al, final Class type) {
		if (type == DBObject.class) {
			al.add(set);
		}
		for (ZDisplayable zd : set.getZDisplayables()) {
			if (DBObject.class == type || Displayable.class == type) {
				al.add(zd);
			} else if (zd.getClass() == type) {
				al.add(zd);
			}
		}
		for (Layer layer : set.getLayers()) {
			if (DBObject.class == type || Layer.class == type) {
				al.add(layer);
			}
			for (Displayable ob : layer.getDisplayables()) {
				if (DBObject.class == type || Displayable.class == type) {
					if (ob instanceof LayerSet) find((LayerSet)ob, al, type);
					else al.add(ob);
				} else if (ob.getClass() == type) {
					al.add(ob);
				}
			}
		}
	}

	/** Remove from the tables if there. */
	static public void remove(final Displayable displ) {
		if (null == displ || null == instance) return;
		final int n_tabs = instance.search_tabs.getTabCount();
		boolean repaint = false;
		final int selected = instance.search_tabs.getSelectedIndex();
		for (int t=0; t<n_tabs; t++) {
			java.awt.Component c = instance.search_tabs.getComponentAt(t);
			JTable table = (JTable)((JScrollPane)c).getViewport().getComponent(0);
			DisplayableTableModel data = (DisplayableTableModel)table.getModel();
			if (data.remove(displ)) {
				// remake table (can't delete just a row, only columns??)
				String name = instance.search_tabs.getTitleAt(t);
				instance.search_tabs.removeTabAt(t);
				// need to think about it TODO // if (0 == data.getRowCount()) continue;
				instance.search_tabs.insertTab(name, null, instance.makeTable(data), "", t);
				if (t == selected) repaint = true;
				try { Thread.sleep(100); } catch (Exception e) {} // I love swing
			}
		}
		if (repaint) {
			Utils.updateComponent(instance.search_frame);
			instance.search_tabs.setSelectedIndex(selected);
		}
	}

	/** Repaint (refresh the text in the cells) only if any of the selected tabs in any of the search frames contains the given object in its rows. */
	static public void repaint(final Object ob) {
		if (null == instance) return;
		SwingUtilities.invokeLater(new Runnable() { public void run() {
			final int selected = instance.search_tabs.getSelectedIndex();
			if (-1 == selected) return;
			java.awt.Component c = instance.search_tabs.getComponentAt(selected);
			JTable table = (JTable)((JScrollPane)c).getViewport().getComponent(0);
			DisplayableTableModel data = (DisplayableTableModel)table.getModel();
			if (data.contains(ob)) {
				Utils.updateComponent(instance.search_frame);
			}
		}});
	}
}
