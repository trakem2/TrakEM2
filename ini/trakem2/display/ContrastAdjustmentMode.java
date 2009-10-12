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

import ij.process.ImageProcessor;
import ij.IJ;

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

	public ContrastAdjustmentMode(final Display display, final List<Displayable> selected) {
		super(display, selected);
		super.initThreads(); // so screePatchRanges exist and are filled
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
