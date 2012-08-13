package ini.trakem2.display;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;

public class FreeHandProfile {

	private Profile profile;

	private boolean updateProfile = true;

	private double[][] all_points; // points {{x0,y0},{x1,y1} , ... , {xn,yn}}

	// painted originally

	private int lastBezierIndex; // index in all_points where last time used

	// to insert bezier

	private int nPoints = 0;

	private double[][] l_points; // points {{x0,y0},{x1,y1} , ... , {xn,yn}}

	// left bezier points

	private double[][] r_points; // points {{x0,y0},{x1,y1} , ... , {xn,yn}}

	// right bezier points

	private double[][] b_points; // points {{x0,y0},{x1,y1} , ... , {xn,yn}}

	// center bezier points

	private boolean insertAtBeginOfProfile;

	private boolean insertAtEndOfProfile;

	private int nBeziers = 0;

	private boolean isVisible = true;

	private double mouseVelocity[] = new double[2];

	private double mousePosition[] = new double[2];

	private int[] bezierMousePoxIndexes; // points {{x0,y0},{x1,y1} , ... ,

	// {xn,yn}}

	private static final double MOUSE_MASS = 10;

	private double[][] newBezierPoints = new double[4][2];

	//only used in closed beziers
	private int startIndex;

	private static final double MAX_ACCUMULATED_ERROR_PER_BEZIER = 0.3;

	private static final int GRADIENT_DESCENT_SAMPLE_COUNT = 20;

	private static final double LEARNING_RATE = 0.1;

	public FreeHandProfile(Profile profile) {

		this.profile = profile;
	}

	public void mousePressed(int x_p, int y_p) {
		// array initialisation
		// mousePosition[0] = x_p;
		// mousePosition[1] = y_p;
		x_p -= profile.getX();
		y_p -= profile.getY();

		all_points = new double[5][2];

		l_points = new double[5][2];
		r_points = new double[5][2];
		b_points = new double[5][2];
		bezierMousePoxIndexes = new int[5];

		if (profile.hasPoints()) {
			if (profile.isClosed()) {
				startIndex = profile.getNearestPointIndex(x_p,y_p);
				
				all_points[0][0] = profile.p[0][startIndex];
				all_points[0][1] = profile.p[1][startIndex];
				
				l_points[0][0] = profile.p_l[0][startIndex];
				l_points[0][1] = profile.p_l[1][startIndex];
				b_points[0][0] = profile.p[0][startIndex];
				b_points[0][1] = profile.p[1][startIndex];
				r_points[0][0] = profile.p_r[0][startIndex];
				r_points[0][1] = profile.p_r[1][startIndex];
				
			} else {
				double[][] first = profile.getFirstPoint();
				double[][] last = profile.getLastPoint();
				double[][] nearer;

				double firstDist = squaredDist(first[1][0], first[1][1], x_p, y_p);
				double lastDist = squaredDist(last[1][0], last[1][1], x_p, y_p);
				if (firstDist < lastDist) {
					// minDist = firstDist;
					insertAtBeginOfProfile = true;
					nearer = first;
				} else {
					insertAtEndOfProfile = true;
					nearer = last;
				}

				all_points[0][0] = nearer[1][0];
				all_points[0][1] = nearer[1][1];

				l_points[0][0] = nearer[0][0];
				l_points[0][1] = nearer[0][1];
				b_points[0][0] = nearer[1][0];
				b_points[0][1] = nearer[1][1];
				r_points[0][0] = nearer[2][0];
				r_points[0][1] = nearer[2][1];
			}
		} else {

			all_points[0][0] = x_p;
			all_points[0][1] = y_p;
			l_points[0][0] = r_points[0][0] = b_points[0][0] = x_p;
			l_points[0][1] = r_points[0][1] = b_points[0][1] = y_p;
		}
		// all_points[1][0] = x_p;
		// all_points[1][1] = y_p;
		// nPoints = 2;

		mousePosition[0] = all_points[0][0] + profile.getX();
		mousePosition[1] = all_points[0][1] + profile.getY();
		bezierMousePoxIndexes[0] = 0;
		for (int i = 0; i < 4; i++) {
			this.newBezierPoints[i][0] = all_points[0][0];
			this.newBezierPoints[i][1] = all_points[0][1];
		}
		nPoints = 1;
		nBeziers = 1;
		lastBezierIndex = 0;
	}

	public void mouseDragged(MouseEvent me, int x_d, int y_d, double dx, double dy) {
		mouseVelocity[0] = dx / MOUSE_MASS + (1 - 1d / MOUSE_MASS) * mouseVelocity[0];
		mouseVelocity[1] = dy / MOUSE_MASS + (1 - 1d / MOUSE_MASS) * mouseVelocity[1];
		mousePosition[0] += mouseVelocity[0];
		mousePosition[1] += mouseVelocity[1];
		insertPoint(mousePosition[0] - profile.getX(), mousePosition[1] - profile.getY());
		adjustNewBezierEndPoint();
		double[][] targetPoints = calculateMouseSamples(lastBezierIndex, nPoints - 1);
		interpolateTargetSamples(targetPoints, 20);
		double medError = getMedSquaredError(targetPoints);
		if (medError > MAX_ACCUMULATED_ERROR_PER_BEZIER && (nPoints - lastBezierIndex > 20)) {
			fixateCurrentBezier();
			if (nBeziers > 3)
				smoothBezierPoint(nBeziers - 2);
		}
	}

	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {

		adjustNewBezierEndPoint();
		double[][] targetPoints = calculateMouseSamples(lastBezierIndex, nPoints - 1);
		interpolateTargetSamples(targetPoints, 20);
		fixateCurrentBezier();
		if (nBeziers > 3)
			smoothBezierPoint(nBeziers - 2);
		r_points[nBeziers - 1][0] = b_points[nBeziers - 1][0];
		r_points[nBeziers - 1][1] = b_points[nBeziers - 1][1];

		if (updateProfile) {
			double[][] tmp_p_l = new double[2][nBeziers];
			double[][] tmp_p = new double[2][nBeziers];
			double[][] tmp_p_r = new double[2][nBeziers];

			if (insertAtBeginOfProfile) {
				for (int i = 0; i < 2; i++) {
					for (int j = 0; j < nBeziers; j++) {
						tmp_p_r[i][j] = l_points[nBeziers - 1 - j][i];
						tmp_p[i][j] = b_points[nBeziers - 1 - j][i];
						tmp_p_l[i][j] = r_points[nBeziers - 1 - j][i];
					}
				}
				profile.addPointsAtBegin(tmp_p_l, tmp_p, tmp_p_r);

			} else if (insertAtEndOfProfile) {
				for (int i = 0; i < 2; i++) {
					for (int j = 0; j < nBeziers; j++) {
						tmp_p_l[i][j] = l_points[j][i];
						tmp_p[i][j] = b_points[j][i];
						tmp_p_r[i][j] = r_points[j][i];
					}
				}
				profile.addPointsAtEnd(tmp_p_l, tmp_p, tmp_p_r);
			} else {
				
				if(profile.closed && profile.hasPoints())
				{
					int endIndex = profile.getNearestPointIndex(this.all_points[this.nPoints-1][0], this.all_points[this.nPoints-1][1]);
					for (int i = 0; i < 2; i++) {
						for (int j = 0; j < nBeziers; j++) {
							tmp_p_l[i][j] = l_points[j][i];
							tmp_p[i][j] = b_points[j][i];
							tmp_p_r[i][j] = r_points[j][i];
						}
					}
					profile.insertBetween(startIndex, endIndex,tmp_p_l, tmp_p, tmp_p_r);
				}
				else
				{
					for (int i = 0; i < 2; i++) {
						for (int j = 0; j < nBeziers; j++) {
							tmp_p_l[i][j] = l_points[j][i];
							tmp_p[i][j] = b_points[j][i];
							tmp_p_r[i][j] = r_points[j][i];
						}
					}
					profile.setPoints(tmp_p_l, tmp_p, tmp_p_r);
				}
			}

		}
		// update x,y,width,height
		profile.calculateBoundingBox(true);
		profile.repaint(false);
	}

	private void smoothBezierPoint(int i) {
		double[][] bezier1 = new double[4][2]; // bezier points defining the
		// first curve
		double[][] bezier2 = new double[4][2]; // bezier points defining the
		// second curve
		int firstMousePointIndex = bezierMousePoxIndexes[i - 1];
		int middleMousePointIndex = bezierMousePoxIndexes[i];
		int lastMousePointIndex = bezierMousePoxIndexes[i + 1];
		double[][] targetpoints1 = calculateMouseSamples(firstMousePointIndex, middleMousePointIndex);
		double[][] targetpoints2 = calculateMouseSamples(middleMousePointIndex, lastMousePointIndex);
		bezier1[0][0] = b_points[i - 1][0];
		bezier1[0][1] = b_points[i - 1][1];
		bezier1[1][0] = r_points[i - 1][0];
		bezier1[1][1] = r_points[i - 1][1];
		bezier1[2][0] = l_points[i][0];
		bezier1[2][1] = l_points[i][1];
		bezier1[3][0] = b_points[i][0];
		bezier1[3][1] = b_points[i][1];

		bezier2[0][0] = b_points[i][0];
		bezier2[0][1] = b_points[i][1];
		bezier2[1][0] = r_points[i][0];
		bezier2[1][1] = r_points[i][1];
		bezier2[2][0] = l_points[i + 1][0];
		bezier2[2][1] = l_points[i + 1][1];
		bezier2[3][0] = b_points[i + 1][0];
		bezier2[3][1] = b_points[i + 1][1];

		smoothBezier(bezier1, bezier2, targetpoints1, targetpoints2);

		b_points[i - 1][0] = bezier1[0][0];
		b_points[i - 1][1] = bezier1[0][1];
		r_points[i - 1][0] = bezier1[1][0];
		r_points[i - 1][1] = bezier1[1][1];
		l_points[i][0] = bezier1[2][0];
		l_points[i][1] = bezier1[2][1];
		b_points[i][0] = bezier1[3][0];
		b_points[i][1] = bezier1[3][1];

		b_points[i][0] = bezier2[0][0];
		b_points[i][1] = bezier2[0][1];
		r_points[i][0] = bezier2[1][0];
		r_points[i][1] = bezier2[1][1];
		l_points[i + 1][0] = bezier2[2][0];
		l_points[i + 1][1] = bezier2[2][1];
		b_points[i + 1][0] = bezier2[3][0];
		b_points[i + 1][1] = bezier2[3][1];

	}

	// bezier1 and 2 are also return values !!
	private void smoothBezier(double[][] bezier1, double[][] bezier2, double[][] targetpoints1, double[][] targetpoints2) {
		double alpha;
		double beta;

		double[] dP;

		double[] p00;
		double[] p02;
		double[] p03;

		double[] p10;
		double[] p11;

		// take star points
		p03 = bezier1[0];
		p02 = bezier1[1];
		p11 = bezier2[2];
		p10 = bezier2[3];

		p00 = new double[2];
		p00[0] = bezier2[0][0];
		p00[1] = bezier2[0][1];

		// find good start points for other values
		dP = new double[2];
		dP[0] = bezier1[2][0] - bezier2[1][0];
		dP[1] = bezier1[2][1] - bezier2[1][1];

		alpha = dist(bezier1[2][0], bezier1[2][1], p00[0], p00[1]) / dist(0, 0, dP[0], dP[1]);
		beta = -dist(bezier2[1][0], bezier2[1][1], p00[0], p00[1]) / dist(0, 0, dP[0], dP[1]);

		for (int i = 0; i < 20; i++)// curve approximation
		{
			double dAlpha = 0;
			double dBeta = 0;
			double[] ddP = new double[2];
			double[] dp02 = new double[2];
			double[] dp00 = new double[2];
			double[] dp11 = new double[2];

			// calc gradients for first curve
			for (int j = 0; j < GRADIENT_DESCENT_SAMPLE_COUNT; j++) {
				double t = (j + 1) / (GRADIENT_DESCENT_SAMPLE_COUNT + 1);

				// calculate part which is for all gradients the same
				double constPartX = -targetpoints1[j][0];
				constPartX += p00[0] * t * t * (3 - 2 * t);
				constPartX += 3 * alpha * dP[0] * t * t * (1 - t);
				constPartX += 3 * p02[0] * t * (1 - t) * (1 - t);
				constPartX += p03[0] * (1 - t) * (1 - t) * (1 - t);

				double constPartY = -targetpoints1[j][1];
				constPartY += p00[1] * t * t * (3 - 2 * t);
				constPartY += 3 * alpha * dP[1] * t * t * (1 - t);
				constPartY += 3 * p02[1] * t * (1 - t) * (1 - t);
				constPartY += p03[1] * (1 - t) * (1 - t) * (1 - t);

				dAlpha += 3 * (constPartX * dP[0] + constPartY * dP[1]) * t * t * (1 - t);

				ddP[0] += 3 * constPartX * alpha * t * t * (1 - t);
				ddP[1] += 3 * constPartY * alpha * t * t * (1 - t);

				dp02[0] += 3 * constPartX * t * (1 - t) * (1 - t);
				dp02[1] += 3 * constPartY * t * (1 - t) * (1 - t);

				dp00[0] += constPartX * t * t * (3 - 2 * t);
				dp00[1] += constPartY * t * t * (3 - 2 * t);
			}

			// calc gradients for second curve
			for (int j = 0; j < GRADIENT_DESCENT_SAMPLE_COUNT; j++) {
				double t = 1 - ((j + 1) / (GRADIENT_DESCENT_SAMPLE_COUNT + 1));

				// calculate part which is for all gradients the same
				double constPartX = -targetpoints2[j][0];
				constPartX += p00[0] * t * t * (3 - 2 * t);
				constPartX += 3 * beta * dP[0] * t * t * (1 - t);
				constPartX += 3 * p11[0] * t * (1 - t) * (1 - t);
				constPartX += p10[0] * (1 - t) * (1 - t) * (1 - t);

				double constPartY = -targetpoints2[j][1];
				constPartY += p00[1] * t * t * (3 - 2 * t);
				constPartY += 3 * beta * dP[1] * t * t * (1 - t);
				constPartY += 3 * p11[1] * t * (1 - t) * (1 - t);
				constPartY += p10[1] * (1 - t) * (1 - t) * (1 - t);

				dBeta += 3 * (constPartX * dP[0] + constPartY * dP[1]) * t * t * (1 - t);

				ddP[0] += 3 * constPartX * beta * t * t * (1 - t);
				ddP[1] += 3 * constPartY * beta * t * t * (1 - t);

				dp11[0] += 3 * constPartX * t * (1 - t) * (1 - t);
				dp11[1] += 3 * constPartY * t * (1 - t) * (1 - t);

				dp00[0] += constPartX * t * t * (3 - 2 * t);
				dp00[1] += constPartY * t * t * (3 - 2 * t);

			}

			alpha -= LEARNING_RATE * dAlpha;
			beta -= LEARNING_RATE * dBeta;

			dP[0] -= LEARNING_RATE * ddP[0];
			dP[1] -= LEARNING_RATE * ddP[1];

			p02[0] -= LEARNING_RATE * dp02[0];
			p02[1] -= LEARNING_RATE * dp02[1];

			p00[0] -= LEARNING_RATE * dp00[0];
			p00[1] -= LEARNING_RATE * dp00[1];

			p11[0] -= LEARNING_RATE * dp11[0];
			p11[1] -= LEARNING_RATE * dp11[1];
		}

		bezier1[0] = p03;
		bezier1[1] = p02;
		bezier1[2][0] = p00[0] + alpha * dP[0];
		bezier1[2][1] = p00[1] + alpha * dP[1];
		bezier1[3][0] = p00[0];
		bezier1[3][1] = p00[1];
		bezier2[0][0] = p00[0];
		bezier2[0][1] = p00[1];
		bezier2[1][0] = p00[0] + beta * dP[0];
		bezier2[1][1] = p00[1] + beta * dP[1];
		bezier2[2] = p11;
		bezier2[3] = p10;
	}

	private void fixateCurrentBezier() {

		//if endpoint = startpoint
		if (this.newBezierPoints[3][0] == this.newBezierPoints[0][0]
				&& this.newBezierPoints[3][1] == this.newBezierPoints[0][1]) {
			return;
		}
		// ensure array size
		if (nBeziers >= b_points.length) {
			double[][] tmp = new double[b_points.length + 5][2];
			System.arraycopy(b_points, 0, tmp, 0, b_points.length);
			b_points = tmp;
			tmp = new double[l_points.length + 5][2];
			System.arraycopy(l_points, 0, tmp, 0, l_points.length);
			l_points = tmp;
			tmp = new double[r_points.length + 5][2];
			System.arraycopy(r_points, 0, tmp, 0, r_points.length);
			r_points = tmp;
			int[] tmp2 = new int[bezierMousePoxIndexes.length + 5];
			System.arraycopy(bezierMousePoxIndexes, 0, tmp2, 0, bezierMousePoxIndexes.length);
			bezierMousePoxIndexes = tmp2;
		}

		b_points[nBeziers - 1][0] = this.newBezierPoints[3][0];
		b_points[nBeziers - 1][1] = this.newBezierPoints[3][1];

		r_points[nBeziers - 1][0] = this.newBezierPoints[2][0];
		r_points[nBeziers - 1][1] = this.newBezierPoints[2][1];

		l_points[nBeziers][0] = this.newBezierPoints[1][0];
		l_points[nBeziers][1] = this.newBezierPoints[1][1];

		b_points[nBeziers][0] = this.newBezierPoints[0][0];
		b_points[nBeziers][1] = this.newBezierPoints[0][1];

		lastBezierIndex = nPoints - 1;
		bezierMousePoxIndexes[nBeziers] = lastBezierIndex;
		nBeziers++;

		for (int i = 0; i < 4; i++) {
			newBezierPoints[i][0] = newBezierPoints[0][0];
			newBezierPoints[i][1] = newBezierPoints[0][1];
		}

	}

	private double getMedSquaredError(double[][] targetPoints) {
		double ret = 0;
		for (int i = 0; i < GRADIENT_DESCENT_SAMPLE_COUNT; i++) {
			double t = (double) (i + 1) / (GRADIENT_DESCENT_SAMPLE_COUNT + 1);
			double oneMinT = 1 - t;
			double dx = newBezierPoints[0][0] * t * t * t;
			dx += 3 * newBezierPoints[1][0] * t * t * oneMinT;
			dx += 3 * newBezierPoints[2][0] * t * oneMinT * oneMinT;
			dx += newBezierPoints[3][0] * oneMinT * oneMinT * oneMinT;
			dx -= targetPoints[i][0];
			dx = dx * dx;
			double dy = newBezierPoints[0][1] * t * t * t;
			dy += 3 * newBezierPoints[1][1] * t * t * oneMinT;
			dy += 3 * newBezierPoints[2][1] * t * oneMinT * oneMinT;
			dy += newBezierPoints[3][1] * oneMinT * oneMinT * oneMinT;
			dy -= targetPoints[i][1];
			dy = dy * dy;

			ret += dx + dy;
		}
		return ret / GRADIENT_DESCENT_SAMPLE_COUNT;
	}

	private void interpolateTargetSamples(double[][] targetPoints, int stepCount) {
		for (int i = 0; i < stepCount; i++) {
			gradientDescentStep(targetPoints);
		}

	}

	private void gradientDescentStep(double[][] targetPoints) {
		double dX1 = 0;
		double dX2 = 0;
		double dY1 = 0;
		double dY2 = 0;

		// summed gradient
		for (int i = 0; i < GRADIENT_DESCENT_SAMPLE_COUNT; i++) {
			double t = (double) (i + 1) / (GRADIENT_DESCENT_SAMPLE_COUNT + 1);
			double oneMinT = 1 - t;
			double tmp = newBezierPoints[0][0] * t * t * t;
			tmp += 3 * newBezierPoints[1][0] * t * t * oneMinT;
			tmp += 3 * newBezierPoints[2][0] * t * oneMinT * oneMinT;
			tmp += newBezierPoints[3][0] * oneMinT * oneMinT * oneMinT;
			tmp -= targetPoints[i][0];
			tmp *= 6 * t * oneMinT;
			dX1 += tmp * t;
			dX2 += tmp * oneMinT;

			tmp = newBezierPoints[0][1] * t * t * t;
			tmp += 3 * newBezierPoints[1][1] * t * t * oneMinT;
			tmp += 3 * newBezierPoints[2][1] * t * oneMinT * oneMinT;
			tmp += newBezierPoints[3][1] * oneMinT * oneMinT * oneMinT;
			tmp -= targetPoints[i][1];
			tmp *= 6 * t * oneMinT;
			dY1 += tmp * t;
			dY2 += tmp * oneMinT;
		}
		dX1 /= GRADIENT_DESCENT_SAMPLE_COUNT;
		dX2 /= GRADIENT_DESCENT_SAMPLE_COUNT;
		dY1 /= GRADIENT_DESCENT_SAMPLE_COUNT;
		dY2 /= GRADIENT_DESCENT_SAMPLE_COUNT;

		newBezierPoints[1][0] -= LEARNING_RATE * dX1;
		newBezierPoints[1][1] -= LEARNING_RATE * dY1;
		newBezierPoints[2][0] -= LEARNING_RATE * dX2;
		newBezierPoints[2][1] -= LEARNING_RATE * dY2;
	}

	private double[][] calculateMouseSamples(int fromIndex, int toIndex) {
		double[][] mouseSamples = new double[GRADIENT_DESCENT_SAMPLE_COUNT][2];

		double totExamplesLength = 0;
		double[] lengthToPoints = new double[toIndex - fromIndex + 1];
		lengthToPoints[0] = 0;
		// find total length of painted lines
		for (int i = fromIndex + 1; i <= toIndex; i++) {
			totExamplesLength += this.dist(all_points[i][0], all_points[i][1], all_points[i - 1][0],
					all_points[i - 1][1]);
			lengthToPoints[i - fromIndex] = totExamplesLength;
		}

		if (totExamplesLength == 0) {
			for (int i = 0; i < GRADIENT_DESCENT_SAMPLE_COUNT; i++) {
				mouseSamples[i][0] = all_points[fromIndex][0];
				mouseSamples[i][1] = all_points[fromIndex][1];
			}
			return mouseSamples;
		}
		int index = 0;
		double factor;
		for (int i = 0; i < GRADIENT_DESCENT_SAMPLE_COUNT; i++) {
			double distFromFirst = (i + 1) * totExamplesLength / (GRADIENT_DESCENT_SAMPLE_COUNT + 1);
			while (lengthToPoints[index] < distFromFirst)
				index++;

			factor = (distFromFirst - lengthToPoints[index - 1]) / (lengthToPoints[index] - lengthToPoints[index - 1]);

			mouseSamples[i][0] = factor * (all_points[index + fromIndex][0] - all_points[index + fromIndex - 1][0])
					+ all_points[index + fromIndex][0];
			mouseSamples[i][1] = factor * (all_points[index + fromIndex][1] - all_points[index + fromIndex - 1][1])
					+ all_points[index + fromIndex][1];
		}
		return mouseSamples;
	}

	private void adjustNewBezierEndPoint() {
		// this.newBezierPoints[2][0] += all_points[nPoints - 1][0]
		// - all_points[nPoints - 2][0];
		// this.newBezierPoints[2][1] += all_points[nPoints - 1][1]
		// - all_points[nPoints - 2][1];
		this.newBezierPoints[0][0] = all_points[nPoints - 1][0];
		this.newBezierPoints[0][1] = all_points[nPoints - 1][1];
	}

	private void insertPoint(double x, double y) {
		if (x == all_points[nPoints - 1][0] && y == all_points[nPoints - 1][1])
			return;

		// dynamicly increase arraysize

		if (this.nPoints >= all_points.length) {
			double[][] tmp = new double[all_points.length + 5][2];
			System.arraycopy(all_points, 0, tmp, 0, all_points.length);
			all_points = tmp;
		}

		all_points[nPoints] = new double[] { x, y };
		this.nPoints++;

	}

	/**
	 * for abort (ESC pressed for instance)
	 * 
	 */
	public void abort() {
		this.updateProfile = false;
		this.isVisible = false;
	}

	public void paint(Graphics g, double magnification, Rectangle srcRect, boolean active) {
		if (isVisible) {
			Graphics2D g2d = (Graphics2D) g;
			g2d.translate((profile.getX() - srcRect.x) * magnification, (profile.getY() - srcRect.y) * magnification);
			// g2d.translate((- srcRect.x) * magnification,
			// (- srcRect.y) * magnification);

			g.setColor(Color.GREEN);
			for (int i = lastBezierIndex + 1; i < nPoints; i++) {
				g.drawLine((int) (all_points[i][0] * magnification), (int) (all_points[i][1] * magnification),
						(int) (all_points[i - 1][0] * magnification), (int) (all_points[i - 1][1] * magnification));
			}

			// g.setColor(Color.RED);
			// for (int i = 0; i < targetSamples.length; i++) {
			// g.fillOval((int) (targetSamples[i][0] * magnification - 2),
			// (int) (targetSamples[i][1] * magnification - 2), 5, 5);
			// g.drawString("" + i,
			// (int) (targetSamples[i][0] * magnification),
			// (int) (targetSamples[i][1] * magnification));
			// }

			g.setColor(Color.BLUE);
			double lastX;
			double lastY;
			for (int i = 1; i < nBeziers; i++) {
				lastX = b_points[i][0];
				lastY = b_points[i][1];

				for (int j = 0; j <= 20; j++) {
					double t = (double) j / 20;
					double newX = b_points[i - 1][0] * t * t * t;
					newX += 3 * r_points[i - 1][0] * t * t * (1 - t);
					newX += 3 * l_points[i][0] * t * (1 - t) * (1 - t);
					newX += b_points[i][0] * (1 - t) * (1 - t) * (1 - t);

					double newY = b_points[i - 1][1] * t * t * t;
					newY += 3 * r_points[i - 1][1] * t * t * (1 - t);
					newY += 3 * l_points[i][1] * t * (1 - t) * (1 - t);
					newY += b_points[i][1] * (1 - t) * (1 - t) * (1 - t);

					g.drawLine((int) (lastX * magnification), (int) (lastY * magnification),
							(int) (newX * magnification), (int) (newY * magnification));
					lastX = newX;
					lastY = newY;
				}
			}

			//			g.setColor(Color.GREEN);
			//			lastX = newBezierPoints[3][0];
			//			lastY = newBezierPoints[3][1];
			//			for (int i = 0; i <= 20; i++) {
			//				double t = (double) i / 20;
			//				double newX = newBezierPoints[0][0] * t * t * t;
			//				newX += 3 * newBezierPoints[1][0] * t * t * (1 - t);
			//				newX += 3 * newBezierPoints[2][0] * t * (1 - t) * (1 - t);
			//				newX += newBezierPoints[3][0] * (1 - t) * (1 - t) * (1 - t);
			//
			//				double newY = newBezierPoints[0][1] * t * t * t;
			//				newY += 3 * newBezierPoints[1][1] * t * t * (1 - t);
			//				newY += 3 * newBezierPoints[2][1] * t * (1 - t) * (1 - t);
			//				newY += newBezierPoints[3][1] * (1 - t) * (1 - t) * (1 - t);
			//
			//				g.drawLine((int) (lastX * magnification),
			//						(int) (lastY * magnification),
			//						(int) (newX * magnification),
			//						(int) (newY * magnification));
			//				lastX = newX;
			//				lastY = newY;
			//			}

			g2d.translate(-(profile.getX() - srcRect.x) * magnification, -(profile.getY() - srcRect.y) * magnification);
			// g2d.translate((srcRect.x) * magnification,
			// ( srcRect.y) * magnification);

		}
	}

	private double squaredDist(double x1, double y1, double x2, double y2) {
		double dx = x1 - x2;
		double dy = y1 - y2;
		return dx * dx + dy * dy;
	}

	private double dist(double x1, double y1, double x2, double y2) {
		double dx = x1 - x2;
		double dy = y1 - y2;
		return Math.sqrt(dx * dx + dy * dy);
	}

	public int getMouseX() {
		return (int) mousePosition[0];
	}

	public int getMouseY() {
		return (int) mousePosition[1];
	}
}
