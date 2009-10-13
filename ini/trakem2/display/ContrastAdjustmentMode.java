package ini.trakem2.display;

import java.awt.Rectangle;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.image.ColorModel;
import java.awt.event.MouseEvent;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JLabel;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.GridBagConstraints;
import java.awt.BorderLayout;
import java.awt.Insets;

import ini.trakem2.utils.M;
import ini.trakem2.utils.IJError;
import ini.trakem2.display.YesNoDialog;
import ini.trakem2.utils.History;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.Worker;
import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.display.graphics.GraphicsSource;
import ini.trakem2.display.graphics.DefaultGraphicsSource;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Display;
import ini.trakem2.imaging.ContrastPlot;

import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.IJ;

public class ContrastAdjustmentMode extends GroupingMode {

	private MinMaxData min_max = new MinMaxData();

	protected void doPainterUpdate( final Rectangle r, final double m ) {
		for ( final GroupingMode.ScreenPatchRange spr : screenPatchRanges.values()) {
			spr.update( min_max.clone() );
		}
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
			super(range, srcRect, magnification);
		}
		public void update(MinMaxData m) {
			minLabel.setText(Double.toString(m.min));
			maxLabel.setText(Double.toString(m.max));
			plot.update(m.min, m.max);
			//
			// TODO
			//

			Utils.log2("min max are: " + m.min + ", " + m.max);
		}
	}

	static private class MinMaxData {
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

	private final ImageProcessor initial;
	private final ContrastPlot plot;
	private final JLabel minLabel, maxLabel;

	public ContrastAdjustmentMode(final Display display, final List<Displayable> selected) throws Exception {
		super(display, selected);

		// Check that all images are of the same type
		int type = originalPatches.get(0).getType();
		for (Patch p : originalPatches)
			if (p.getType() != type)
				throw new Exception("All images must be of the same type!");

		initial = Patch.makeFlatImage(type, layer, srcRect, magnification, originalPatches, Color.black);
		initial.resetMinAndMax();
		ImageStatistics stats = ImageStatistics.getStatistics(initial, 0, layer.getParent().getCalibrationCopy());
		plot = new ContrastPlot();
		plot.setDefaultMinAndMax(initial.getMin(), initial.getMax());
		plot.setHistogram(stats, Color.black);

		int sliderRange = computeSliderRange();

		// Create GUI
		final JFrame frame = new JFrame("Contrast adjustment");
		frame.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent we) {
				cancel();
				frame.dispose();
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
		mm.setBackground(Color.white);
		final Font monoFont = new Font("Monospaced", Font.PLAIN, 12);
		c.gridy = 1;
		c.insets = new Insets(0, 10, 0, 10);
		c.fill = GridBagConstraints.HORIZONTAL;
		gb.setConstraints(mm, c);
		panel.add(mm);

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
		cm.anchor = GridBagConstraints.EAST;
		gbm.setConstraints(maxLabel, cm);
		mm.add(maxLabel);

		// 3. Min slider
		final JSlider minslider = createSlider(panel, gb, c, "Minimum", monoFont, sliderRange);
		final JSlider maxslider = createSlider(panel, gb, c, "Maximum", monoFont, sliderRange);
		ChangeListener adl = new ChangeListener() {
			public void stateChanged(ChangeEvent ce) {
				min_max.set(minslider.getValue(), maxslider.getValue());
				doPainterUpdate(srcRect, magnification);
			}
		};
		minslider.addChangeListener(adl);
		maxslider.addChangeListener(adl);

		frame.getContentPane().add(panel);
		frame.pack();
		frame.setVisible(true);

		super.initThreads();
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

	private JSlider createSlider(JPanel panel, GridBagLayout gb, GridBagConstraints c, String title, Font font, int sliderRange) {
		JSlider s = new JSlider(JSlider.HORIZONTAL, 0, sliderRange, sliderRange/2);
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
				// TODO
			}
		}, layer.getProject() );

		super.quitThreads();

		return true;
	}
}
