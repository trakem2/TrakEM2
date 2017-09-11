package ini.trakem2.display;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JPanel;
import javax.swing.JScrollBar;

public class RollingPanel extends JPanel implements ComponentListener, AdjustmentListener, MouseWheelListener
{
	private static final long serialVersionUID = 1L;
	private final Display display;
	private final Class<?> clazz;
	private final Map<Displayable,DisplayablePanel> current;
	private final JPanel inner, filler;
	private final JScrollBar scrollBar;
	private final GridBagLayout gbInner;
	private final GridBagConstraints cInner;

	/**
	 * @param display
	 * @param clazz
	 */
	protected RollingPanel(final Display display, final Class<?> clazz) {
		this.display = display;
		this.clazz = clazz;
		//
		this.current = new HashMap<Displayable, DisplayablePanel>();
		this.inner = new JPanel();
		this.filler = new JPanel();
		this.gbInner = new GridBagLayout();
		this.cInner = new GridBagConstraints();
		this.inner.setLayout(this.gbInner);
		this.inner.setBackground(Color.white);
		//
		this.scrollBar = new JScrollBar();
		this.scrollBar.setUnitIncrement(1);
		this.scrollBar.setBlockIncrement(1);
		this.scrollBar.setMaximum(getList().size());
		
		this.setBackground(Color.white);

		final GridBagLayout gb = new GridBagLayout();
		final GridBagConstraints c = new GridBagConstraints();
		
		this.setLayout(gb);
		//
		c.anchor = GridBagConstraints.NORTHWEST;
		//
		c.fill = GridBagConstraints.BOTH;
		c.weightx = 1;
		c.weighty = 1;
		gb.setConstraints(inner, c);
		this.add(inner);
		//
		c.gridx = 1;
		c.weightx = 0;
		c.fill = GridBagConstraints.VERTICAL;
		gb.setConstraints(scrollBar, c);
		this.add(scrollBar);

		// Finally:
		this.addComponentListener(this);
		this.scrollBar.addAdjustmentListener(this);
		this.addMouseWheelListener(this);
		this.scrollBar.addMouseWheelListener(this);
	}

	public final void updateList() {
		try {
			updateList2();
		} catch (Exception e) {
			try {
				// Wait a bit
				Thread.sleep(1000);
				// Try again:
				updateList2();
			} catch (Exception e1) {
				e1.printStackTrace();
			}
		}
	}
	
	synchronized private final void updateList2() {
		final List<? extends Displayable> list = getList();
		final int count = getList().size();
		
		if (list.isEmpty()) {
			this.current.clear();
			if (this.inner.getComponentCount() > 0) {
				this.inner.removeAll();
				this.scrollBar.setMaximum(0);
				this.revalidate();
				//if (clazz == Patch.class) Utils.log2("empty list, removeAll, revalidate");
			}
			return;
		}

		final int numCanFit = (int)Math.floor(inner.getBounds().height / (float)DisplayablePanel.HEIGHT);
		
		if (!(this.scrollBar.getMaximum() == count && this.scrollBar.getVisibleAmount() == numCanFit)) {
			this.scrollBar.getModel().setRangeProperties(
					Math.max(0, this.scrollBar.getValue()),
					numCanFit,
					0, count, false);
		}

		this.current.clear();

		// Scrollbar value:
		int first = this.scrollBar.getValue();
		if (-1 == first) {
			this.scrollBar.setValue(0);
			first = 0;
		}
		// Correct for inverse list:
		first = list.size() - first -1;

		int last = first - numCanFit +1;
		if (last < 0) {
			first = Math.min(numCanFit, count) -1;
			last = 0;
		}

		// Reuse panels only if the exact same number is to be used
		final DisplayablePanel[] toReuse;
		final boolean notReusing;
		if (first - last + 1 != inner.getComponentCount() -1) {
			this.inner.removeAll();
			notReusing = true;
			toReuse = null;
		} else {
			notReusing = false;
			toReuse = new DisplayablePanel[first - last + 1];
			int i = 0;
			for (final Component c : inner.getComponents()) {
				if (DisplayablePanel.class == c.getClass()) {
					toReuse[i++] = (DisplayablePanel)c;
				}
			}
		}

		this.cInner.anchor = GridBagConstraints.NORTHWEST;
		this.cInner.fill = GridBagConstraints.HORIZONTAL;
		this.cInner.weightx = 0;
		this.cInner.weighty = 0;
		this.cInner.gridy = 0;
		for (int i=first, k=0; i >= last ; --i, ++k) {
			final Displayable d = list.get(i);
			final DisplayablePanel dp;
			if (notReusing) {
				dp = new DisplayablePanel(display, d);
				this.gbInner.setConstraints(dp, this.cInner);
				this.inner.add(dp);
				this.cInner.gridy += 1;
			} else {
				dp = toReuse[k];
				dp.set(d);
			}
			this.current.put(d, dp);
		}
		if (notReusing) {
			this.cInner.fill = GridBagConstraints.BOTH;
			this.cInner.weightx = 1;
			this.cInner.weighty = 1;
			this.gbInner.setConstraints(this.filler, this.cInner);
			this.inner.add(this.filler);
			this.inner.validate();
		}
	}

	private final List<? extends Displayable> getList() {
		if (ZDisplayable.class == clazz) {
			return display.getLayerSet().getDisplayableList();
		}
		return display.getLayer().getDisplayables(clazz);
	}
	
	protected boolean isShowing(final Displayable d) {
		return this.current.containsKey(d);
	}

	@Override
	public void componentResized(ComponentEvent e) {
		updateList();
	}

	@Override
	public void componentMoved(ComponentEvent e) {}

	@Override
	public void componentShown(ComponentEvent e) {
		if (0 == this.inner.getComponentCount()) {
			updateList();
		}
	}
	@Override
	public void componentHidden(ComponentEvent e) {}


	/** Adjust the sublist of displayed DisplayablePanel. */
	@Override
	public void adjustmentValueChanged(AdjustmentEvent e) {
		updateList();
	}

	public void scrollToShow(Displayable d) {
		final DisplayablePanel dp = current.get(d);
		if (null == dp) {
			// Linear look-up but it's acceptable
			final List<? extends Displayable> list = getList();
			this.scrollBar.setValue(list.size() - list.indexOf(d) -1);
		} else {
			dp.repaint(); // for select on click to repaint the background
		}
	}

	@Override
	public void update(Graphics g) {
		if (0 == this.inner.getComponentCount()) {
			updateList();
		}
		super.update(g);
	}

	@Override
	public void mouseWheelMoved(MouseWheelEvent e) {
		final int newVal = this.scrollBar.getValue() + (e.getWheelRotation() > 0 ? 1 : -1);
		if (newVal >= 0 && newVal <= this.scrollBar.getMaximum()) {
			this.scrollBar.setValue(newVal);
		}
	}
}
