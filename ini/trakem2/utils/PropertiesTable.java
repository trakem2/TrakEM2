package ini.trakem2.utils;

import java.util.Map;

public interface PropertiesTable {

	/** Returns true on successful addition. */
	public boolean setProperty(String key, String value);

	/** Returns default_value when key not found. */
	public String getProperty(String key, String default_value);

	/** Returns a copy of the properties table. */
	public Map getProperties();
}
