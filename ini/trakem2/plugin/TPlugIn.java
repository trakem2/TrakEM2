package ini.trakem2.plugin;

public interface TPlugIn {

	/** Setup with optional parameters (may be null).
	 *  Returns true of the setup was successful or not interrupted (like by clicking cancel on a dialog). */
	public boolean setup(Object... params);

	/** Run the plugin directly. */
	public Object invoke(Object... params);

	/** Returns true if this plugin can be applied to an Object like @param ob.
	 *  This may or may not be a Displayable object. */
	public boolean applies(Object ob);
}
