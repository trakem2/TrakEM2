package lineage;

import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Hashtable;

import weka.classifiers.Classifier;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;

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

	final static protected String[] attrs = new String[]{"APD", "CPD", "STD", "MPD", "PM", "LEV", "SIM", "PRX", "PRM", "LR", "TR", "CLASS"};

	static private final class Operator {
		final Classifier c = getClassifier();
		final Instances data;
		Operator() {
			ArrayList<Attribute> a = new ArrayList<Attribute>();
			for (int i=0; i<attrs.length-1; i++) {
				a.add(new Attribute(attrs[i])); // numeric
			}
			ArrayList<String> d = new ArrayList<String>();
			d.add("false");
			d.add("true");
			a.add(new Attribute(attrs[attrs.length-1], d)); // nominal attribute
			data = new Instances("Buh", a, 0);
			data.setClassIndex(attrs.length-1); // the CLASS
		}
	}

	public static final boolean classify(final double[] vector) throws Exception {

		// Obtain or generate a Thread-local instance

		Operator op;
		synchronized (table) { // avoid clashes within weka
			final Thread t = Thread.currentThread();
			op = table.get(t);
			if (null == op) {
				op = new Operator();
				table.put(t, op);
			}
		}

		// Future weka versions will use new DenseInstance(1, vector) instead
		final Instance ins = new DenseInstance(1, vector);
		ins.setDataset(op.data);
		// Was trained to return true or false, represented in weka as 0 or 1
		return 1 == ((int) Math.round(op.c.classifyInstance(ins)));
	}

	/** Removes all threads and Instances from the cache tables. */
	static public final void flush () {
		table.clear();
	}
}
