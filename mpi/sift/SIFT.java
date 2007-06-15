

package mpi.sift;

import ij.ImagePlus;
import ij.io.*;

public class SIFT {

	private ImagePlus template;
	private ImagePlus target;

	SIFT() {}

	static public void main(String[] args) {
		if (args.length < 2) {
			log("Need two arguments: the paths to two images");
			return;
		}
		SIFT sift = new SIFT();
		Opener opener = new Opener();
		sift.template = opener.openImage(args[0]);
		sift.target = opener.openImage(args[1]);

		sift.template.setProcessor(null, sift.template.getProcessor().convertToFloat());
		sift.target.setProcessor(null, sift.target.getProcessor().convertToFloat());
	}

	static public void log(String msg) {
		System.out.println(msg);
	}

	public class FeatureMatch {
		Feature f1;
		Feature f2;
		float sq_distance;
	}

	public class Model {
		float dx;
		float dy;
		float rot;
	}
}
