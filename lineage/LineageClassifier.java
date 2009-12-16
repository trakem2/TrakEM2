package lineage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;

import weka.classifiers.Classifier;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.DenseInstance;
import weka.core.Attribute;

import java.util.Hashtable;
import java.util.ArrayList;

/**
 * Lineage classifier class
 * 
 * 2009 Ignacio Arganda-Carreras and Sergio Jimenez-Celorrio
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 */


public class LineageClassifier 
{
	// Deserialize model
	static private Classifier getClassifier() {
		ObjectInputStream ois = null;
		try {
			ois = new ObjectInputStream(LineageClassifier.class.getResourceAsStream("random_forest_top8_w1.1.model"));
			return (Classifier) ois.readObject();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (null != ois) try { ois.close(); } catch (Exception e) { e.printStackTrace(); }
		}
		return null;
	}

	// Hashtable's methods are all synchronized
	static private final Hashtable<Thread,Operator> table = new Hashtable<Thread,Operator>();

	final static private String[] attrs = new String[]{"APD", "CPD", "STD", "MPD", "PM", "LEV", "SIM", "PRX", "PRM", "LR", "TR", "CLASS"};
	final static private ArrayList<Attribute> A = new ArrayList<Attribute>();

	static {
		for (int i=0; i<attrs.length; i++) {
			A.add(new Attribute(attrs[i]));
		}
	}

	static private final class Operator {
		final Classifier c = getClassifier();
		final Instances data = new Instances("Buh", new ArrayList<Attribute>(A), 0);
		Operator() {
			data.setClassIndex(11); // the CLASS
		}
	}

	public static final boolean classify(final double[] vector) throws Exception {

		// Obtain or generate a Thread-local instance
		final Thread t = Thread.currentThread();
		Operator op = table.get(t);
		if (null == op) {
			op = new Operator();
			table.put(t, op);
		}

		try {
			op.data.add(new DenseInstance(1, vector));
			// Was trained to return true or false, represented in weka as 0 or 1
			return 1 == ((int) Math.round(op.c.classifyInstance(op.data.instance(0))));
		} finally {
			op.data.remove(0);
		}
	}

	/** Removes all threads and Instances from the cache tables. */
	static public final void flush () {
		table.clear();
	}
}
