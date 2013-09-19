package ini.trakem2.utils;

import java.io.Serializable;

public interface Filter<T> extends Serializable
{
	public boolean accept(T t);
}