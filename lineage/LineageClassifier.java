package lineage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;

import weka.classifiers.Classifier;
import weka.core.Instance;
import weka.core.Instances;

import java.util.Hashtable;

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
	
	static private Classifier c;
	static {
		// deserialize model
		ObjectInputStream ois;
		try {
			ois = new ObjectInputStream(
					LineageClassifier.class.getResourceAsStream("random_forest_top8_w1.1.model"));

			LineageClassifier.c = (Classifier) ois.readObject();
			ois.close();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			
		}
	}
	
	static private Instances unlabeled;
	static {
		BufferedReader bf = null;
		try{									
			bf = new BufferedReader(
					new InputStreamReader(LineageClassifier.class.getResourceAsStream("unlabeled.arff")));
			unlabeled = new Instances(bf);

			// set class attribute
			unlabeled.setClassIndex(unlabeled.numAttributes() - 1);						
		}
		catch(Exception ex)
		{
			ex.printStackTrace();
		}
		finally
		{
			if(bf != null)
				try {
					bf.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		}
	}

	// Hashtable's methods are all synchronized
	static private final Hashtable<Thread,Instances> table = new Hashtable<Thread,Instances>();

	public static final boolean classify (final double[] vector) throws Exception {				
		// Obtain or generate a Thread-local instance
		final Thread t = Thread.currentThread();
		Instances unlabeled = table.get(t);
		if (null == unlabeled) {
			unlabeled = new Instances(LineageClassifier.unlabeled);
			table.put(t, unlabeled);
		}

		try {
			// For this Thread-local instance, set the vector of parameters and evaluate:
			unlabeled.add(new Instance(1, vector));
			// Was trained to return true or false, represented in weka as 0 or 1
			return 1 == ((int) Math.round(LineageClassifier.c.classifyInstance(unlabeled.instance(0))));
			// clsLabel was the returned double value
			//System.out.println(clsLabel + " -> " + unlabeled.classAttribute().value((int) clsLabel));
		} finally {
			// ... and remove it (WEKA is stateful, meh):
			unlabeled.delete(0);
		}
	}

	/** Removes all threads and Instances from the cache tables. */
	static public final void flush () {
		table.clear();
	}
}
