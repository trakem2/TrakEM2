package ini.trakem2.display;

import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JPanel;
import javax.swing.JScrollBar;

class RollingPanel extends JPanel implements ComponentListener, AdjustmentListener
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
	 * @param source The list of Displayable instances, a fraction of which is shown here.
	 */
	protected RollingPanel(final Display display, final Class<?> clazz) {
		this.display = display;
		this.clazz = clazz;
		
		this.current = new HashMap<Displayable, DisplayablePanel>();
		this.inner = new JPanel();
		this.filler = new JPanel();
		this.scrollBar = new JScrollBar();
		this.scrollBar.addAdjustmentListener(this);
		this.scrollBar.setUnitIncrement(1);
		this.scrollBar.setBlockIncrement(1);
		this.gbInner = new GridBagLayout();
		this.cInner = new GridBagConstraints();
		
		this.inner.setLayout(this.gbInner);
		this.inner.setBackground(Color.white);

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
	}

	public final void updateList() {
		try {
			updateList2();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	private final void updateList2() {
		final List<? extends Displayable> list = getList();
		
		Utils.log2("clazz is " + clazz.getSimpleName() + " and list size: " + list.size() + " and scrollbar maximum: " + scrollBar.getMaximum());
		
		
		if (list.isEmpty()) {
			this.current.clear();
			if (this.inner.getComponentCount() > 0) {
				this.inner.removeAll();
				this.inner.revalidate();
			}
			return;
		}
		// For now, replace all always
		this.current.clear();
		this.inner.removeAll();

		// Scrollbar value:
		int first = this.scrollBar.getValue();
		if (-1 == first) {
			updateScrollBar();
			first = 0;
		}
		final int nShowing = (int)Math.ceil(inner.getBounds().height / (float)DisplayablePanel.HEIGHT);
		if (list.size() - nShowing < first) {
			first = list.size() - nShowing;   // TODO is there an error?
		}
		this.cInner.anchor = GridBagConstraints.NORTHWEST;
		this.cInner.fill = GridBagConstraints.HORIZONTAL;
		this.cInner.weighty = 0;
		this.cInner.gridy = 0;
		for (int i=0; i < nShowing; ++i) {
			Displayable d = list.get(first + i);
			DisplayablePanel dp = new DisplayablePanel(display, d);
			this.current.put(d, dp);
			this.gbInner.setConstraints(dp, this.cInner);
			this.inner.add(dp);
			this.cInner.gridy += 1;
		}
		this.cInner.fill = GridBagConstraints.BOTH;
		this.cInner.weighty = 1;
		this.gbInner.setConstraints(this.filler, this.cInner);
		this.inner.add(this.filler);
		
		Utils.log2("current size: " + current.size());
		
		this.inner.revalidate();
	}
	
	private final void updateScrollBar() {
		final int count = getList().size();
		if (this.scrollBar.getMaximum() != count) {
			this.scrollBar.setMaximum(count);
		}
		this.scrollBar.setVisibleAmount((int) Math.ceil(this.getBounds().height / (float)DisplayablePanel.HEIGHT));
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
		updateScrollBar();
		updateList();
	}

	@Override
	public void componentMoved(ComponentEvent e) {}
	@Override
	public void componentShown(ComponentEvent e) {
		if (0 == this.inner.getComponentCount()) {
			updateScrollBar();
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

	public void scrollToShow(Displayable zd) {
		//TODO
		Utils.log2("scrollToShow not implemented yet");
	}
	
	@Override
	public void update(Graphics g) {
		if (0 == this.inner.getComponentCount()) {
			updateScrollBar();
			updateList();
		}
		super.update(g);
	}
}