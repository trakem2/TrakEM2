package test;

import java.lang.reflect.Field;

import mpicbg.trakem2.transform.MovingLeastSquaresTransform2;

/** Test correctness of {@link MovingLeastSquaresTransform2} init functions. */
public class TestMovingLeastSquaresTransform2
{
	static public final void main(String[] args) {
		try {
			// Preliminary test:
			System.out.println("Float.MIN_VALUE = " + Float.toString(Float.MIN_VALUE));
			System.out.println("Float.MAX_VALUE = " + Float.toString(Float.MAX_VALUE));
			System.out.println((float)Math.pow(10, -45));
			System.out.println(1.4f * (float)Math.pow(10, -45));
			System.out.println((float)Math.pow(10, 38));
			
			// Test string contains Float.MIN_VALUE, .MAX_VALUE, and +1 and -1 of them
			final String large = "affine 2 1.0 1.4E-45 3.4028235E38 " + (1.4E-45 + 1) + " " + (3.4028235E38 - 1) + " 1.0 2330.0 724.0 2330.0 724.0 1.0 2516.0 727.0 2516.0 727.0 1.0 2825.0 739.0 2825.0 739.0 1.0 3002.0002 736.0 3002.0 736.0 1.0 3281.001 736.0 3281.0 736.0 1.0 3482.001 742.0 3482.0 742.0 1.0 3725.001 745.0 3725.0 745.0 1.0 3983.0017 745.0 3983.0 745.0 1.0 4157.0024 739.0 4157.0 739.0 1.0 4325.002 751.0 4325.0 751.0 1.0 4390.9854 993.99994 4391.0 994.0 1.0 4219.9834 1020.9999 4220.0 1021.0 1.0 4018.983 1023.9999 4019.0 1024.0 1.0 3796.983 1017.9999 3797.0 1018.0 1.0 3586.9824 1020.9999 3587.0 1021.0 1.0 3289.981 1035.9998 3290.0 1036.0 1.0 3148.9802 1047.9998 3149.0 1048.0 1.0 2806.9795 1044.9998 2807.0 1045.0 1.0 2578.979 1047.9998 2579.0 1048.0 1.0 2392.9785 1047.9998 2393.0 1048.0 1.0 2371.969 1188.9998 2372.0 1189.0 1.0 2638.9685 1206.9998 2639.0 1207.0 1.0 2851.9688 1206.9998 2852.0 1207.0 1.0 3082.9697 1206.9998 3083.0 1207.0 1.0 3310.97 1206.9998 3311.0 1207.0 1.0 3496.9702 1206.9999 3497.0 1207.0 1.0 3691.9712 1198.0 3692.0 1198.0 1.0 3943.9717 1195.0 3944.0 1195.0 1.0 4123.9717 1195.0 4124.0 1195.0 1.0 4354.9727 1183.0 4355.0 1183.0 1.0 4423.965 1306.0 4424.0 1306.0 1.0 4231.9575 1410.9999 4232.0 1411.0 1.0 3850.957 1413.9999 3845.0 1414.0 1.0 3552.9067 1416.9998 3548.0 1417.0 1.0 3340.6917 1419.9998 3338.0 1420.0 1.0 3172.0889 1422.9995 3170.0 1423.0 1.0 3060.851 1422.9995 3059.0 1423.0 1.0 2823.295 1422.9995 2822.0 1423.0 1.0 2709.1892 1428.9995 2708.0 1429.0 1.0 2553.0269 1428.9995 2552.0 1429.0 1.0 2403.3828 1578.9995 2402.0 1579.0 1.0 2619.5566 1578.9995 2618.0 1579.0 1.0 2895.9922 1587.9996 2894.0 1588.0 1.0 3157.6333 1590.9996 3155.0 1591.0 1.0 3425.6125 1587.9998 3422.0 1588.0 1.0 3729.5825 1588.0 3725.0 1588.0 1.0 3894.6206 1588.0 3890.0 1588.0 1.0 4126.9688 1579.0 4124.0 1579.0 1.0 4348.025 1576.0 4346.0 1576.0 1.0 4452.919 1579.0002 4451.0 1579.0 1.0 4504.8438 1768.0002 4502.0 1768.0 1.0 4295.339 1798.0002 4292.0 1798.0 1.0 4091.9287 1807.0002 4088.0 1807.0 1.0 3792.756 1807.0001 3788.0 1807.0 1.0 3555.6267 1803.9998 3551.0 1804.0 1.0 3306.2095 1809.9998 3302.0 1810.0 1.0 3074.7664 1821.9998 3071.0 1822.0 1.0 2858.3079 1818.9995 2852.0 1819.0 1.0 2621.9727 1818.9995 2618.0 1819.0 1.0 2339.1707 1818.9995 2336.0 1819.0 1.0 2376.23 1998.9995 2372.0 1999.0 1.0 2643.9084 2001.9998 2639.0 2002.0 1.0 2926.463 2010.9998 2924.0 2011.0 1.0 3228.7146 2013.9998 3227.0 2014.0 1.0 3383.458 2016.9999 3380.0 2017.0 1.0 3621.5615 2011.0 3617.0 2011.0 1.0 3828.855 2011.0001 3824.0 2011.0 1.0 4059.7258 2008.0005 4055.0 2008.0 1.0 4179.616 2005.0005 4175.0 2005.0 1.0 4482.1504 1990.0006 4478.0 1990.0 1.0 4501.206 2215.0005 4496.0 2215.0 1.0 4339.3223 2221.0005 4334.0 2221.0 1.0 4009.5066 2224.0005 4004.0 2224.0 1.0 3718.4097 2233.0002 3707.0 2233.0 1.0 3385.523 2227.0002 3380.0 2227.0 1.0 3084.8071 2236.0 3080.0 2236.0 1.0 2850.8462 2235.9998 2843.0 2236.0 1.0 2618.0845 2229.9998 2606.0 2230.0 1.0 2520.2085 2232.9995 2510.0 2233.0 1.0 2305.331 2232.9995 2297.0 2233.0 1.0 2312.5972 2376.9995 2303.0 2380.0 1.0 2613.6096 2379.6626 2603.0 2380.0 1.0 2839.9526 2379.8416 2834.0 2380.0 1.0 2999.8557 2379.891 2993.0 2380.0 1.0 3206.6636 2379.927 3200.0 2380.0 1.0 3441.0208 2382.9553 3434.0 2383.0 1.0 3673.747 2382.9705 3665.0 2383.0 1.0 3853.3145 2382.979 3845.0 2383.0 1.0 4190.8413 2385.987 4184.0 2386.0 1.0 4487.301 2385.996 4481.0 2386.0 1.0 2296.2778 604.01086 2297.0 604.0 1.0 2536.1816 595.017 2537.0 595.0 1.0 2839.126 595.01697 2840.0 595.0 1.0 3118.0815 598.0175 3119.0 598.0 1.0 3364.12 601.01544 3365.0 601.0 1.0 3508.1118 601.01587 3509.0 601.0 1.0 3628.0789 598.01654 3638.0 598.0 1.0 3817.8489 598.016 3824.0 598.0 1.0 4019.6694 604.01337 4022.0 604.0 1.0 4278.367 583.0154 4280.0 583.0 1.0";
			final MovingLeastSquaresTransform2 m = new MovingLeastSquaresTransform2();
			//
			Field fp = mpicbg.models.MovingLeastSquaresTransform2.class.getDeclaredField("p");
			fp.setAccessible(true);
			Field fq = mpicbg.models.MovingLeastSquaresTransform2.class.getDeclaredField("q");
			fq.setAccessible(true);
			Field fw = mpicbg.models.MovingLeastSquaresTransform2.class.getDeclaredField("w");
			fw.setAccessible(true);

			// Reference: Saalfeld's
			m.init(large);
			float a1 = m.getAlpha();
			float[][] p1 = (float[][]) fp.get(m);
			float[][] q1 = (float[][]) fq.get(m);
			float[] w1 = (float[]) fw.get(m);

			// To compare with: mine
			m.init2(large);
			float a2 = m.getAlpha();
			float[][] p2 = (float[][]) fp.get(m);
			float[][] q2 = (float[][]) fq.get(m);
			float[] w2 = (float[]) fw.get(m);
	
			// Test correctness: compare equality of all fields
			int nErrors = 0;
			if (a1 != a2) {
				System.out.println("ERROR with alpha: " + a1 + " != " + a2);
				++nErrors;
			}
			for (int k=p1[0].length -1; k > -1; --k) {
				for (int d=0; d<p1.length; ++d) {
					if (p1[d][k] != p2[d][k]) {
						System.out.println("ERROR with p: " + p1[d][k] + " != " + p2[d][k]);
						++nErrors;
					}
				}
				for (int d=0; d<q1.length; ++d) {
					if (q1[d][k] != q2[d][k]) {
						System.out.println("ERROR with q: " + q1[d][k] + " != " + q2[d][k]);
						++nErrors;
					}
				}
				if (w1[k] != w2[k]) {
					System.out.println("ERROR with w: " + w1[k] + " != " + w2[k]);
					++nErrors;
				}
			}
	
			System.out.println("Number of differences between init1 and init2: " + nErrors);


			// Compare performance
			for (int k=0; k<10; ++k) {
				long t0 = System.currentTimeMillis();
				new MovingLeastSquaresTransform2().init(large);
				long t1 = System.currentTimeMillis();
				System.out.println("init1: " + (t1 - t0) + " ms");
			}
			for (int k=0; k<10; ++k) {
				long t0 = System.currentTimeMillis();
				new MovingLeastSquaresTransform2().init2(large);
				long t1 = System.currentTimeMillis();
				System.out.println("init2: " + (t1 - t0) + " ms");
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
