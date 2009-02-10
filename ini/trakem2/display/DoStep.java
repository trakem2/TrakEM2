package ini.trakem2.display;

public interface DoStep {
	/** Returns true on success. */
	public boolean apply();

	public boolean isEmpty();

	/** May return null. */
	public Displayable getD();
}
