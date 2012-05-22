package ini.trakem2.utils;

public interface Filter<T> {
	public boolean accept(T t);
}