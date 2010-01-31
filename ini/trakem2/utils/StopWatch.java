package ini.trakem2.utils;

public class StopWatch {

	private long start;
	private long start2;

	public StopWatch() {
		this("");
	}
	public StopWatch(String start_msg) {
		start = start2 = System.currentTimeMillis();
		Utils.log("StopWatch started: " + start_msg);
	}
	/** Time elapsed since last call to this method or the start. */
	public void elapsed(String msg) {
		long now = System.currentTimeMillis();
		Utils.log("Elapsed: " + (now - start2) + " at: " + msg);
		start2 = now;
	}
	/** Total time transcurred from the start. */
	public void cumulative() {
		long now = System.currentTimeMillis();
		Utils.log("Cumulative: " + (now - start));
	}
}
