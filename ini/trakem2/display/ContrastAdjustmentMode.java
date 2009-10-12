package ini.trakem2.display;

import java.awt.event.MouseEvent;
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

import ini.trakem2.utils.M;
import ini.trakem2.utils.Utils;
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

import ij.plugin.frame.ContrastAdjuster;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import ij.IJ;
import java.awt.event.AdjustmentListener;
import java.awt.event.AdjustmentEvent;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.Button;
import java.awt.Scrollbar;
import java.lang.reflect.Field;

public class ContrastAdjustmentMode extends GroupingMode {

	private MinMaxData min_max = new MinMaxData();

	protected void doPainterUpdate( final Rectangle r, final double m ) {
		for ( final GroupingMode.ScreenPatchRange spr : screenPatchRanges.values()) {
			spr.update( min_max );
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

	static private class ScreenPatchRange extends GroupingMode.ScreenPatchRange<MinMaxData> {
		ScreenPatchRange( final PatchRange range, final Rectangle srcRect, final double magnification )
		{
			super(range, srcRect, magnification);
		}
		public void update(MinMaxData m) {
			// TODO
		}
	}

	static private class MinMaxData {
		double min = 0,
		       max = 0;
	}

	private ImagePlus imp;
	private ContrastAdjuster ca;

	public ContrastAdjustmentMode(final Display display, final List<Displayable> selected) {
		super(display, selected);
		super.initThreads(); // so screePatchRanges exist and are filled

		// TODO need to wait properly for Updater to generate the lists of screenPatchRanges
		try {
			Thread.sleep(1000);
		} catch (Exception e) {}

		// Most surface range:
		GroupingMode.ScreenPatchRange top = screenPatchRanges.get(originalPatches.get(originalPatches.size() -1));

		imp = new ImagePlus("", top.ip.duplicate());
		ca = (ContrastAdjuster) IJ.runPlugIn(imp, "ij.plugin.frame.ContrastAdjuster", "");
		setupListeners(ca);
	}

	private Button getButton(ContrastAdjuster ca, String field) throws Exception {
		Field f = ContrastAdjuster.class.getDeclaredField(field);
		f.setAccessible(true);
		return (Button) f.get(ca);
	}
	private Scrollbar getSlider(ContrastAdjuster ca, String field) throws Exception {
		Field f = ContrastAdjuster.class.getDeclaredField(field);
		f.setAccessible(true);
		return (Scrollbar) f.get(ca);
	}

	private void setPreActionListener(Button[] bs, ActionListener pre) {
		for (Button b : bs) {
			ActionListener[] all = b.getActionListeners();
			for (ActionListener a : all) b.removeActionListener(a);
			b.addActionListener(pre);
			for (ActionListener a : all) b.addActionListener(a);
		}
	}

	private void setPreAdjustmentListener(Scrollbar[] bs, AdjustmentListener pre) {
		for (Scrollbar b : bs) {
			AdjustmentListener[] all = b.getAdjustmentListeners();
			for (AdjustmentListener a : all) b.removeAdjustmentListener(a);
			b.addAdjustmentListener(pre);
			for (AdjustmentListener a : all) b.addAdjustmentListener(a);
		}
	}

	private void setupListeners(ContrastAdjuster ca) {
		try {
			// Update on sliders
			setPreAdjustmentListener(new Scrollbar[]{getSlider(ca, "minSlider"),
				                                 getSlider(ca, "maxSlider"),
							         getSlider(ca, "contrastSlider"),
							         getSlider(ca, "brightnessSlider")},
						 new AdjustmentListener() {
							 public void adjustmentValueChanged(AdjustmentEvent e) {
								 painter.update();
							 }
						 });
			// Update on pushing buttons
			setPreActionListener(new Button[]{getButton(ca, "autoB"),
							  getButton(ca, "resetB")},
					     new ActionListener() {
				public void actionPerformed(ActionEvent ae) {
					painter.update();
				}
			});

			// Apply on pushing "Apply" button
			setPreActionListener(new Button[]{getButton(ca, "applyB")}, new ActionListener() {
				public void actionPerformed(ActionEvent ae) {
					apply(); // will also close the dialog
				}
			});

			// Cancel on closing dialog window
			ca.addWindowListener(new WindowAdapter() {
				public void windowClosing(WindowEvent we) {
					cancel();
				}
			});
		} catch (Exception e) {
			IJError.print(e);
		}
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
				if (null != ca) ca.dispose();
				// TODO
			}
		}, layer.getProject() );

		super.quitThreads();

		return true;
	}
}
