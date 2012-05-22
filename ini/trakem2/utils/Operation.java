package ini.trakem2.utils;

public interface Operation<I,O>
{
	public I apply(O o);
}