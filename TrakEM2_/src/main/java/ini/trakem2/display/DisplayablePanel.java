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
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;


public final class DisplayablePanel extends JPanel implements MouseListener {

	private static final long serialVersionUID = 1L;

	static public final int HEIGHT = 52;

	static private ImageIcon LOCKED = new ImageIcon(DisplayablePanel.class.getResource("/img/locked.png"));
	static private ImageIcon UNLOCKED = new ImageIcon(DisplayablePanel.class.getResource("/img/unlocked.png"));
	static private ImageIcon VISIBLE = new ImageIcon(DisplayablePanel.class.getResource("/img/visible.png"));
	static private ImageIcon INVISIBLE = new ImageIcon(DisplayablePanel.class.getResource("/img/invisible.png"));
	static private ImageIcon LINKED = new ImageIcon(DisplayablePanel.class.getResource("/img/linked.png"));
	static private ImageIcon UNLINKED = new ImageIcon(DisplayablePanel.class.getResource("/img/unlinked.png"));

	static private final Font SMALL = new Font("Courier", Font.ITALIC, 11);
	static private final Color GRAYISH = new Color(50, 50, 50);

	private JCheckBox c, c_locked, c_linked;
	private JLabel title, title2, idlabel;
	private JPanel titles;
	private SnapshotPanel sp;

	private Display display;
	private Displayable d;

	final private ML listener = new ML();

	public DisplayablePanel(Display display, Displayable d) {
		this.display = display;
		this.d = d;

		this.c = new JCheckBox();
		this.c.setSelected(d.isVisible());
		this.c.addMouseListener(listener);
		this.c.setIcon(INVISIBLE);
		this.c.setSelectedIcon(VISIBLE);
		this.c.setBackground(Color.white);
		Dimension maxdim = new Dimension(26, 14);
		this.c.setPreferredSize(maxdim);
		this.c.setMaximumSize(maxdim);

		this.c_locked = new JCheckBox();
		this.c_locked.setIcon(UNLOCKED);
		this.c_locked.setSelectedIcon(LOCKED);
		this.c_locked.setSelected(d.isLocked2());
		this.c_locked.addMouseListener(listener);
		this.c_locked.setBackground(Color.white);
		Dimension maxdim10 = new Dimension(26, 10);
		this.c_locked.setPreferredSize(maxdim10);
		this.c_locked.setMaximumSize(maxdim10);

		this.c_linked = new JCheckBox();
		this.c_linked.setIcon(UNLINKED);
		this.c_linked.setSelectedIcon(LINKED);
		this.c_linked.setSelected(d.isLinked());
		this.c_linked.addMouseListener(listener);
		this.c_linked.setBackground(Color.white);
		this.c_linked.setPreferredSize(maxdim10);
		this.c_linked.setMaximumSize(maxdim10);

		this.sp = new SnapshotPanel(display, d);
		title = new JLabel();
		title.addMouseListener(this);
		title2 = new JLabel();
		title2.addMouseListener(this);
		idlabel = new JLabel("#" + d.getId());
		idlabel.setFont(SMALL);
		idlabel.setForeground(GRAYISH);
		titles = new JPanel();
		updateTitle();
		
		
		GridBagLayout gb = new GridBagLayout();
		GridBagConstraints co = new GridBagConstraints();
		this.setLayout(gb);


		// Column of checkboxes
		co.anchor = GridBagConstraints.NORTHWEST;
		co.fill = GridBagConstraints.NONE;
		co.gridx = 0;
		co.gridy = 0;
		gb.setConstraints(c, co);
		add(c);
		//
		co.gridy = 1;
		co.anchor = GridBagConstraints.WEST;
		co.fill = GridBagConstraints.VERTICAL;
		co.weighty = 1;
		gb.setConstraints(c_locked, co);
		add(c_locked);
		//
		co.gridy = 2;
		co.anchor = GridBagConstraints.SOUTHWEST;
		co.weighty = 0;
		gb.setConstraints(c_linked, co);
		add(c_linked);
		
		// Snapshot panel
		co.anchor = GridBagConstraints.NORTHWEST;
		co.fill = GridBagConstraints.NONE;
		co.gridx = 1;
		co.gridy = 0;
		co.gridheight = 3;
		gb.setConstraints(sp, co);
		add(sp);
		
		// Column of strings
		co.gridheight = 1;
		co.weightx = 1;
		co.fill = GridBagConstraints.HORIZONTAL;
		co.gridx = 2;
		co.gridy = 0;
		gb.setConstraints(title, co);
		add(title);
		//
		co.gridy = 1;
		co.anchor = GridBagConstraints.WEST;
		co.fill = GridBagConstraints.BOTH;
		co.weighty = 1;
		gb.setConstraints(title2, co);
		add(title2);
		//
		co.gridy = 2;
		co.anchor = GridBagConstraints.SOUTHWEST;
		co.fill = GridBagConstraints.HORIZONTAL;
		co.weighty = 0;
		gb.setConstraints(idlabel, co);
		add(idlabel);

		setMinimumSize(new Dimension(230, DisplayablePanel.HEIGHT));
		setPreferredSize(new Dimension(248, DisplayablePanel.HEIGHT));

		addMouseListener(this);
		setBackground(Color.white);
		setBorder(BorderFactory.createLineBorder(Color.black));
	}

	/** For instance-recycling purposes. */
	public void set(final Displayable d) {
		this.d = d;
		c.setSelected(d.isVisible());
		c_locked.setSelected(d.isLocked2());
		c_linked.setSelected(d.isLinked());
		updateTitle();
		sp.set(d);
	}

	public void paint(final Graphics g) {
		if (null == g) return;
		if (display.getSelection().contains(d)) {
			if (null != display.getActive() && display.getActive() == d) { // can be null when initializing ... because swing is designed with built-in async
				setBackground(Color.cyan);
			} else {
				setBackground(Color.pink);
			}
		} else {
			setBackground(Color.white);
		}
		super.paint(g);
	}

	@Override
	public void setBackground(Color c) {
		super.setBackground(c);
		if (null != titles) {
			titles.setBackground(c);
			title.setBackground(c);
			title2.setBackground(c);
		}
	}

	private String makeUpdatedTitle() {
		if (null == d) { Utils.log2("null d "); return ""; }
		else if (null == d.getTitle()) { Utils.log2("null title for " + d); return ""; }
		final Class<?> c = d.getClass();
		if (c == Patch.class) {
			return d.getTitle();
		} else if (c == DLabel.class) {
			return d.getTitle().replace('\n', ' ');
		} else {
			// gather name of the enclosing object in the project tree
			return d.getProject().getMeaningfulTitle2(d);
		}
	}

	static private int MAX_CHARS = 20;

	public void updateTitle() {
		idlabel.setText("#" + d.getId());
		String t = makeUpdatedTitle();
		if (t.length() <= MAX_CHARS) {
			title.setText(t);
			title2.setText("");
			return;
		}
		// else split at MAX_CHARS
		// First try to see if it can be cut nicely
		int lastbracket = t.lastIndexOf('[');
		int end = -1,
		    start = -1;
		if (lastbracket -1 <= MAX_CHARS && -1 != lastbracket) { // there's a space in front of the [
			end = lastbracket -1;
			start = lastbracket;
		} else {
			end = start = MAX_CHARS;
		}
		title.setText(t.substring(0, end));

		if (t.length() - start -1 > MAX_CHARS) {
			title2.setText(t.substring(start, start + 7) + "..." + t.substring(t.length() -10));
		} else {
			title2.setText(t.substring(start));
		}

		title.setToolTipText(t);
		title2.setToolTipText(t);
	}

	private class ML extends MouseAdapter {
		public void mousePressed(final MouseEvent me) {
			display.dispatcher.exec(new Runnable() {
				public void run() {
					JCheckBox source = (JCheckBox) me.getSource();
					if (source == c) {
						if (!source.isSelected()) {
							d.setVisible(true);
						} else {
							// Prevent hiding when transforming
							if (Display.isTransforming(d)) {
								Utils.showStatus("Transforming! Can't change visibility.", false);
								SwingUtilities.invokeLater(new Runnable() { public void run() {
									c.setSelected(true);
								}});
								return;
							}
							d.setVisible(false);
						}
					} else if (source == c_locked) {
						final String[] members = new String[]{"locked"};
						if (!source.isSelected()) {
							// Prevent locking while transforming
							if (Display.isTransforming(d)) {
								Utils.logAll("Transforming! Can't lock.");
								SwingUtilities.invokeLater(new Runnable() { public void run() {
									c_locked.setSelected(false);
								}});
								return;
							}
							d.getLayerSet().addDataEditStep(d, members);
							d.setLocked(true);
							d.getLayerSet().addDataEditStep(d, members);
						} else {
							d.getLayerSet().addDataEditStep(d, members);
							d.setLocked(false);
							d.getLayerSet().addDataEditStep(d, members);
						}
						// Update lock checkboxes of linked Displayables, except of this one
						Collection<Displayable> lg = d.getLinkedGroup(null);
						if (null != lg) {
							lg.remove(d); // not this one!
							Display.updateCheckboxes(lg, LOCK_STATE, d.isLocked2());
						}
					} else if (source == c_linked) {
						// Prevent linking/unlinking while transforming
						if (Display.isTransforming(d)) {
							Utils.logAll("Transforming! Can't modify linking state.");
							SwingUtilities.invokeLater(new Runnable() { public void run() {
								c_linked.setSelected(d.isLinked());
							}});
							return;
						}

						final Set<Displayable> hs;

						if (!source.isSelected()) {
							final Rectangle box = d.getBoundingBox();
							hs = new HashSet<Displayable>(d.getLayer().find(box, true)); // only those visible and overlapping
							hs.addAll(d.getLayerSet().findZDisplayables(d.getLayer(), box, true));
							if (hs.size() > 1) {
								d.getLayerSet().addDataEditStep(hs, new String[]{"data"}); // "data" contains links, because links are dependent on bounding box of data
								for (final Displayable other : hs) {
									if (other == d) continue;
									d.link(other);
								}

								d.getLayerSet().addDataEditStep(hs, new String[]{"data"}); // "data" contains links, because links are dependent on bounding box of data
							} else {
								// Nothing to link, restore icon
								SwingUtilities.invokeLater(new Runnable() { public void run() {
									c_linked.setSelected(false);
								}});
							}
						} else {
							hs = d.getLinkedGroup(null);
							d.getLayerSet().addDataEditStep(hs, new String[]{"data"});
							d.unlink();
							d.getLayerSet().addDataEditStep(hs, new String[]{"data"});
						}

						// Update link checkboxes of linked Displayables, except of this one
						if (null != hs) {
							hs.remove(d); // not this one!
							if (hs.size() > 0) {
								Display.updateCheckboxes(hs, LINK_STATE);
								Display.updateCheckboxes(hs, LOCK_STATE);
							}
						}

						// Recompute list of links in Selection
						Display.updateSelection(Display.getFront());
					}
				}
			});
		}
	}

	public void mousePressed(final MouseEvent me) {
		if (display.isTransforming()) return;
		display.select(d, me.isShiftDown());
		if (me.isPopupTrigger() || (ij.IJ.isMacOSX() && me.isControlDown()) || MouseEvent.BUTTON2 == me.getButton() || 0 != (me.getModifiers() & Event.META_MASK)) {
			display.getPopupMenu().show(DisplayablePanel.this, me.getX(), me.getY());
		}
	}

	public void mouseReleased(MouseEvent me) {}
	public void mouseEntered(MouseEvent me) {}
	public void mouseExited (MouseEvent me) {}
	public void mouseClicked(MouseEvent me) {}

	public String toString() {
		return "Displayable panel for " + d.toString();
	}

	static public final int LOCK_STATE = 1;
	static public final int VISIBILITY_STATE = 2;
	static public final int LINK_STATE = 4;

	protected void updateCheckbox(final int cb, final boolean state) {
		switch(cb) {
			case LOCK_STATE:
				c_locked.setSelected(state);
				break;
			case VISIBILITY_STATE:
				c.setSelected(state);
				d.getLayerSet().clearScreenshots();
				break;
			case LINK_STATE:
				c_linked.setSelected(state);
				break;
			default:
				Utils.log2("Ooops: don't know what to do with checkbox code " + cb);
				break;
		}
	}

	protected void updateCheckbox(final int cb) {
		switch(cb) {
			case LOCK_STATE:
				c_locked.setSelected(d.isLocked2());
				break;
			case VISIBILITY_STATE:
				c.setSelected(d.isVisible());
				d.getLayerSet().clearScreenshots();
				break;
			case LINK_STATE:
				c_linked.setSelected(d.isLinked());
				break;
			default:
				Utils.log2("Ooops: don't know what to do with checkbox code " + cb);
				break;
		}
	}
}
