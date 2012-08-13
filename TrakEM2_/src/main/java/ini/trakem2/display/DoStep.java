package ini.trakem2.display;

public interface DoStep {

	// The actions accepted by apply(int)
	static public final int UNDO = 0;
	static public final int REDO = 1;

	/** Returns true on success. */
	public boolean apply(int action);

	public boolean isEmpty();

	/** May return null. */
	public Displayable getD();

	public boolean isIdenticalTo(Object ob); // bypassing equals
}
