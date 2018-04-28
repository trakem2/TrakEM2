package ini.trakem2.display;

import ij.IJ;
import ij.measure.Measurements;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ini.trakem2.imaging.ContrastPlot;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Future;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

public class ContrastAdjustmentMode extends GroupingMode {

	private MinMaxData min_max = new MinMaxData();

	protected void doPainterUpdate( final Rectangle r, final double m ) {
		try {
			MinMaxData md = min_max.clone();
			final HashMap<Paintable, GroupingMode.ScreenPatchRange<?>> screenPatchRanges = this.screenPatchRanges; // keep a pointer to the current list
			for ( final GroupingMode.ScreenPatchRange<?> spr : screenPatchRanges.values()) {
				if (screenPatchRanges != this.screenPatchRanges) {
					// List has been updated; restart painting
					// TODO should it call itself:  doPainterUpdate( r, m );
					break;
				}
				((ScreenPatchRange)spr).update( md );
			}
		} catch (Exception e) {}
	}

	private class ContrastAdjustmentSource extends GroupingMode.GroupedGraphicsSource {
		public void paintOnTop(final Graphics2D g, final Display display, final Rectangle srcRect, final double magnification) {
			// do nothing
		}
	}

	protected GroupingMode.GroupedGraphicsSource createGroupedGraphicSource() {
		return new ContrastAdjustmentSource();
	}

	protected ScreenPatchRange createScreenPathRange(final PatchRange range, final Rectangle srcRect, final double magnification) {
		return new ScreenPatchRange(range, srcRect, magnification);
	}

	private class ScreenPatchRange extends GroupingMode.ScreenPatchRange<MinMaxData> {

		ScreenPatchRange( final PatchRange range, final Rectangle srcRect, final double magnification )
		{
			// The super constructor creates an appropriate alpha mask
			super(range, srcRect, magnification);
			super.transformedImage = makeImage(null, null);
		}
		@Override
		protected BufferedImage makeImage( final ImageProcessor ignored, final FloatProcessor mask )
		{
			if (null == transformed) return null; // not yet ready

			// Use the super.mask, which never changes. super.transformedMask is empty, don't use it!
			return super.makeImage(transformed, super.mask);
		}
		@Override
		public void update(MinMaxData m) {
			// Transform min and max from slider values to image values
			double[] mm = toImage(m.min, m.max);
			transformed.reset();
			transformed.setMinAndMax(mm[0], mm[1]);
			super.transformedImage = makeImage(null, super.mask);
		}
	}

	private final double[] toImage(double slider_min, double slider_max) {
		double imin = initial.getMin();
		double imax = initial.getMax();
		double ratio = (imax-imin) / sliderRange;
		return new double[]{imin + slider_min * ratio, slider_max * ratio};
	}

	/** Expected min,max in slider values, which may be considerably smaller than the proper image min and max. */
	private final void updateLabelsAndPlot(double min, double max) {
		double[] m = toImage(min, max);
		minLabel.setText(Utils.cutNumber(m[0], 1));
		maxLabel.setText(Utils.cutNumber(m[1], 1));
		plot.update(m[0], m[1]);
	}

	static private class MinMaxData {
		/** Min and max in slider values, not in image values. */
		double min = 0,
		       max = 0;
		public MinMaxData() {}
		public MinMaxData(double min, double max) {
			set(min, max);
		}
		synchronized public void set(double min, double max) {
			this.min = min;
			this.max = max;
		}
		synchronized public MinMaxData clone() {
			return new MinMaxData(min ,max);
		}
	}

	private ImageProcessor initial, transformed;
	private final JFrame frame;
	private final ContrastPlot plot;
	private final JLabel minLabel, maxLabel;
	private int sliderRange;

	public ContrastAdjustmentMode(final Display display, final List<Displayable> selected) throws Exception {
		super(display, selected);

		// Check that all images are of the same type
		int type = originalPatches.get(0).getType();
		for (Patch p : originalPatches)
			if (p.getType() != type)
				throw new Exception("All images must be of the same type!\nFirst offending image: " + p);

		// Create an ImageProcessor of the correct type (Short, Float, etc.)
		ArrayList<Patch> patches = new ArrayList<Patch>(originalPatches);
		Patch first = patches.get(0);
		int pad = (int)(ScreenPatchRange.pad / magnification);
		Rectangle box = new Rectangle(srcRect.x - pad, srcRect.y - pad, srcRect.width + 2*pad, srcRect.height + 2*pad);
		initial = Patch.makeFlatImage(first.getType(), layer, box, magnification, patches, Color.black);
		initial.resetMinAndMax();
		transformed = initial.duplicate();
		transformed.setMinAndMax(first.getMin(), first.getMax());
		transformed.snapshot();

		Utils.log2("transformed min, max: " + transformed.getMin() + ", " + transformed.getMax());
		ImageStatistics stats = ImageStatistics.getStatistics(transformed, Measurements.AREA + Measurements.MEAN + Measurements.MODE + Measurements.MIN_MAX, layer.getParent().getCalibrationCopy());
		Utils.log2("stats.min " + stats.min + ", stats.max " + stats.max);
		// correct min and max, which for some reason can be wrong (the min can be zero, for example):
		if (stats.min < initial.getMin()) stats.min = initial.getMin();
		if (stats.max > initial.getMax()) stats.max = initial.getMax();

		// stats is giving a minimum of zero even if its wrong
		plot = new ContrastPlot(initial.getMin(), initial.getMax(), first.getMin(), first.getMax());
		plot.setHistogram(stats, Color.black);

		this.sliderRange = computeSliderRange();

		// Create GUI
		this.frame = new JFrame("Contrast adjustment");
		frame.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent we) {
				display.getCanvas().cancelTransform();
			}
		});
		final JPanel panel = new JPanel();
		panel.setBackground(Color.white);
		final GridBagLayout gb = new GridBagLayout();
		final GridBagConstraints c = new GridBagConstraints();
		panel.setLayout(gb);

		// 1. Plot
		c.gridx = 0;
		c.gridy = 0;
		c.fill = GridBagConstraints.NONE;
		c.anchor = GridBagConstraints.CENTER;
		c.insets = new Insets(10, 10, 0, 10);
		gb.setConstraints(plot, c);
		panel.add(plot);

		// 2. min,max labels
		final JPanel mm = new JPanel();
		mm.setMinimumSize(new Dimension(plot.getWidth(), 15));
		mm.setBackground(Color.white);
		final Font monoFont = new Font("Monospaced", Font.PLAIN, 12);

		GridBagLayout gbm = new GridBagLayout();
		GridBagConstraints cm = new GridBagConstraints();
		mm.setLayout(gbm);
		minLabel = new JLabel("      ");
		minLabel.setFont(monoFont);
		minLabel.setBackground(Color.white);
		maxLabel = new JLabel("      ");
		maxLabel.setFont(monoFont);
		maxLabel.setBackground(Color.white);
		cm.gridx = 0;
		cm.gridy = 0;
		cm.anchor = GridBagConstraints.WEST;
		gbm.setConstraints(minLabel, cm);
		mm.add(minLabel);

		cm.gridx = 1;
		cm.anchor = GridBagConstraints.CENTER;
		cm.fill = GridBagConstraints.HORIZONTAL;
		cm.weightx = 1;
		JPanel empty = new JPanel();
		empty.setBackground(Color.white);
		gbm.setConstraints(empty, cm);
		mm.add(empty);

		cm.weightx = 0;
		cm.fill = GridBagConstraints.NONE;
		cm.gridx = 2;
		cm.anchor = GridBagConstraints.EAST;
		gbm.setConstraints(maxLabel, cm);
		mm.add(maxLabel);
		gbm = null; // defensive programming
		cm = null;

		c.gridy = 1;
		c.insets = new Insets(0, 10, 0, 10);
		c.fill = GridBagConstraints.HORIZONTAL;
		gb.setConstraints(mm, c);
		panel.add(mm);
		
		Utils.log2("first min, max " + first.getMin() + ", " + first.getMax());
		
		// 3. Sliders
		double ratio = sliderRange / (initial.getMax() - initial.getMin());
		double firstMin = (first.getMin() - initial.getMin()) * ratio;
		double firstMax = (first.getMax() - initial.getMin()) * ratio;
		plot.update(first.getMin(), first.getMax());
		
		
		int sliderMin = (int)firstMin;
		int sliderMax = (int)firstMax;
		// Prevent potential errors
		if (sliderMin < 0) sliderMin = 0;
		if (sliderMax > sliderRange -1) sliderMax = sliderRange -1;		
		if (sliderMin > sliderMax) sliderMin = sliderMax;
		Utils.log2("After checking, slider min and max values are: " + sliderMin + ", " + sliderMax + " for range " + sliderRange);
		
		final JSlider minslider = createSlider(panel, gb, c, "Minimum", monoFont, sliderRange, sliderMin);
		final JSlider maxslider = createSlider(panel, gb, c, "Maximum", monoFont, sliderRange, sliderMax);
		ChangeListener adl = new ChangeListener() {
			public void stateChanged(ChangeEvent ce) {
				double smin = minslider.getValue();
				double smax = maxslider.getValue();
				Utils.log2("smin, smax: " + smin + ", " + smax);
				min_max.set(smin, smax);
				updateLabelsAndPlot(smin, smax);
				//doPainterUpdate(srcRect, magnification);
				painter.update();
			}
		};
		minslider.addChangeListener(adl);
		maxslider.addChangeListener(adl);

		// 4. Buttons
		final JButton cancel = new JButton("Cancel");
		final JButton apply = new JButton("Apply");
		ActionListener actlis = new ActionListener() {
			public void actionPerformed(ActionEvent ae) {
				Object source = ae.getSource();
				if (cancel == source) {
					display.getCanvas().cancelTransform();
				} else if (apply == source) {
					display.getCanvas().applyTransform();
				}
			}
		};
		cancel.addActionListener(actlis);
		apply.addActionListener(actlis);

		JPanel buttons = new JPanel();
		buttons.setBackground(Color.white);
		gbm = new GridBagLayout();
		buttons.setLayout(gbm);
		cm = new GridBagConstraints();
		cm.gridx = 0;
		cm.gridy = 0;
		cm.weightx = 0;
		cm.anchor = GridBagConstraints.WEST;
		cm.fill = GridBagConstraints.NONE;
		gbm.setConstraints(cancel, cm);
		buttons.add(cancel);
		
		JPanel space = new JPanel();
		space.setBackground(Color.white);
		cm.gridx = 1;
		cm.weightx = 1;
		cm.anchor = GridBagConstraints.CENTER;
		cm.fill = GridBagConstraints.HORIZONTAL;
		gbm.setConstraints(space, cm);
		buttons.add(space);
		
		cm.gridx = 2;
		cm.weightx = 0;
		cm.anchor = GridBagConstraints.EAST;
		cm.fill = GridBagConstraints.NONE;
		gbm.setConstraints(apply, cm);
		buttons.add(apply);

		gbm = null; // defensive programming
		cm = null;

		c.gridy += 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		gb.setConstraints(buttons, c);
		panel.add(buttons);

		frame.getContentPane().add(panel);
		
		Utils.invokeLater(new Runnable() { public void run() {

			min_max.set(minslider.getValue(), maxslider.getValue());
			
			frame.pack();

			// after calling pack
			Dimension dim = new Dimension(plot.getWidth(), 15);
			minslider.setMinimumSize(dim);
			maxslider.setMinimumSize(dim);

			frame.pack(); // again

			ij.gui.GUI.center(frame);
			frame.setAlwaysOnTop(true);

			frame.setVisible(true);

			ContrastAdjustmentMode.super.initThreads();
		}});
	}

	private int computeSliderRange() {
		double defaultMin = initial.getMin();
		double defaultMax = initial.getMax();
		int valueRange = (int)(defaultMax - defaultMin);
		int newSliderRange = valueRange;
		if (newSliderRange>640 && newSliderRange<1280) {
			newSliderRange /= 2;
		} else if (newSliderRange>=1280) {
			newSliderRange /= 5;
		}
		if (newSliderRange < 256) newSliderRange = 256;
		if (newSliderRange > 1024) newSliderRange = 1024;
		return newSliderRange;
	}

	private JSlider createSlider(JPanel panel, GridBagLayout gb, GridBagConstraints c, String title, Font font, int sliderRange, int start) {
		
		Utils.log2("createSlider range: " + sliderRange + ", start: " + start);
		
		JSlider s = new JSlider(JSlider.HORIZONTAL, 0, sliderRange, start);
		s.setPaintLabels(false);
		s.setPaintTicks(false);
		s.setBackground(Color.white);
		c.gridy++;
		c.insets = new Insets(2, 10, 0, 10);
		gb.setConstraints(s, c);
		panel.add(s);
		JLabel l = new JLabel(title);
		l.setBackground(Color.white);
		l.setFont(font);
		c.gridy++;
		c.insets = new Insets(0, 10, IJ.isMacOSX() ? 4 : 0, 0);
		JPanel p = new JPanel();
		p.setBackground(Color.white);
		p.setLayout(new FlowLayout(FlowLayout.CENTER, 0, 0));
		gb.setConstraints(p, c);
		p.add(l);
		panel.add(p);
		return s;
	}

	public void mousePressed( MouseEvent me, int x_p, int y_p, double magnification ) {}
	public void mouseDragged( MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old ) {}
	public void mouseReleased( MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r ) {}
	public boolean isDragging() { return false; }

	private final void setUndoState() {
		layer.getParent().addEditStep(new Displayable.DoEdits(new HashSet<Displayable>(originalPatches)).init(new String[]{"data"}));
	}

	public boolean apply() {
		/* Set undo step to reflect initial state before any transformations */
		setUndoState();

		Bureaucrat.createAndStart( new Worker.Task( "Applying transformations" ) {
			public void exec() {
				// 1. Close dialog
				frame.dispose();

				// 2. Set min and max
				final double[] m = toImage(min_max.min, min_max.max);

				final Collection<Future<?>> fus = new ArrayList<Future<?>>();

				// Submit all for regeneration
				for (Patch p : originalPatches) {
					p.setMinAndMax(m[0], m[1]);
					fus.add(p.getProject().getLoader().regenerateMipMaps(p));
				}

				// Wait until all done
				for (Future<?> fu : fus) {
					try {
						fu.get();
					} catch (Throwable t) {
						IJError.print(t);
					}
				}

				// To reflect final state
				setUndoState();
			}
		}, layer.getProject() );

		super.quitThreads();

		return true;
	}

	@Override
	public boolean cancel() {
		super.cancel();
		frame.dispose();
		return true;
	}
}