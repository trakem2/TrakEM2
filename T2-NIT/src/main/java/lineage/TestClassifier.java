package lineage;

public class TestClassifier {

	/**
	 * @param args
	 * @throws Exception 
	 */
	public static void main(String[] args) throws Exception 
	{
		double[] vector1false = new double[]{44.82099268281488, 2509.9755902376332, 4.17997816552596, 44.01337238153222, 0.5, 90.310861092961,0.49473684210526314,26.42079568671193,21.65718717197861,0.49473685 };
		double[] vector1true = new double[]{4.316538344816301,211.51037889599874,1.232740370018113,4.104254912581286,0.9591836734693877,15.479368036619153,0.94,4.230207577919975,4.0338740453401325,0.94};
		double[] vector2true = new double[]{1.3097114405061632,123.11287540757934,0.4747039016335009,1.25947461007616,0.9893617021276596,19.180691644667583,0.9789473684210527,1.2959250042903088,1.2732241865138358,1.0215054};

		System.out.println(" false? = " + LineageClassifier.classify(vector1false));
		System.out.println(" true? = " + LineageClassifier.classify(vector1true));
		System.out.println(" true? = " + LineageClassifier.classify(vector2true));
	}

}
